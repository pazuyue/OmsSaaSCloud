package com.oms.inventory;

import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.InventoryMutationService;
import com.oms.inventory.service.impl.rule.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.inventory.service.impl.InventoryQueryService.number;

public class DailyAllocationTest {
    static AnnotationConfigApplicationContext context;static JdbcTemplate db;static InventoryMutationService inventory;static AllocationWorkspaceService workspace;static DailyAllocationService daily;
    @Configuration @Import(ProductInventoryQueryTest.Config.class)
    static class Config {@Bean DailyAllocationService daily(){return new DailyAllocationService();}@Bean DailyAllocationScanner scanner(){return new DailyAllocationScanner();}}
    @BeforeAll static void init(){Assumptions.assumeTrue(System.getenv("INVENTORY_TEST_URL")!=null);context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);inventory=context.getBean(InventoryMutationService.class);workspace=context.getBean(AllocationWorkspaceService.class);daily=context.getBean(DailyAllocationService.class);}
    @AfterAll static void stop(){if(context!=null)context.close();}
    @BeforeEach void clear(){for(String table:Arrays.asList("rule_stock_daily_item","rule_stock_daily_run","rule_stock_reservation_event","rule_stock_order_reservation","rule_stock_reservation","rule_stock_result","rule_stock_channel_info","rule_stock_store_code_info","rule_stock_goods_info","rule_stock_info","oms_channel_inventory","wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))db.update("DELETE FROM "+table);}
    long scalar(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    int revision(long id){return (int)scalar("SELECT revision FROM rule_stock_info WHERE id=?",id);}
    void stock(String sku,int quantity){WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setSkuSn(sku);b.setStoreCode("W1");b.setBatchCode("B1");b.setZpActualNumber(quantity);b.setCpActualNumber(10);inventory.receive(b,"IN-"+sku);}
    AllocationDraft draft(int priority,int percent){AllocationDraft d=new AllocationDraft();d.setRuleName("日常验证");d.setRuleType(1);d.setAllocationType(1);d.setRuleMode(2);d.setRuleRange(1);d.setStartTime(LocalDateTime.now().minusMinutes(1));d.setEndTime(LocalDateTime.now().plusHours(1));d.setIntervalMinutes(5);d.setDailyPriority(priority);d.setStores(Arrays.asList("W1"));AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(1);c.setChannelName("渠道1");c.setPercentage(BigDecimal.valueOf(percent));d.getChannels().add(c);return d;}
    long create(AllocationDraft d){long id=workspace.save("VERIFY",d,"tester");workspace.submit("VERIFY",id,revision(id),false);daily.command("VERIFY",id,revision(id),"ENABLE","tester");return id;}
    long create(){return create(draft(100,100));}
    Map<String,Object> latest(long id){return (Map<String,Object>)daily.runs("VERIFY",id,1,20).getRows().get(0);}
    void due(long id){db.update("UPDATE rule_stock_info SET next_run_at=NOW() WHERE id=?",id);}

    @Test void repeatedRoundsRecalculateAvailableOnlyAndPreserveAllReservations(){
        stock("A",100);inventory.reserve("VERIFY",Arrays.asList("W1"),"A",BigDecimal.valueOf(30),"OTHER-LOCK",false);
        db.update("INSERT INTO oms_channel_inventory(company_code,sku_sn,channel_id,available_stock,allocated_stock,reserved_stock,frozen_stock) VALUES ('VERIFY','A',1,9,30,10,5)");long id=create();daily.tick("VERIFY",id);
        assertEquals(55,scalar("SELECT available_stock FROM oms_channel_inventory"));assertEquals(30,scalar("SELECT allocated_stock FROM oms_channel_inventory"));assertEquals(10,scalar("SELECT reserved_stock FROM oms_channel_inventory"));assertEquals(5,scalar("SELECT frozen_stock FROM oms_channel_inventory"));assertEquals(30,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));assertEquals(70,scalar("SELECT zp_available_number FROM wms_inventory_batch"));assertEquals("SUCCESS",latest(id).get("status"));
        daily.tick("VERIFY",id);assertEquals(1,daily.runs("VERIFY",id,1,20).getTotal());due(id);daily.tick("VERIFY",id);assertEquals(2,daily.runs("VERIFY",id,1,20).getTotal());assertEquals(55,scalar("SELECT available_stock FROM oms_channel_inventory"));assertEquals(0,scalar("SELECT COUNT(*) FROM rule_stock_reservation"));assertEquals(0,scalar("SELECT COUNT(*) FROM rule_stock_result"));
        inventory.reserve("VERIFY",Arrays.asList("W1"),"A",BigDecimal.valueOf(70),"ALL-LOCK",false);due(id);daily.tick("VERIFY",id);assertEquals(0,scalar("SELECT available_stock FROM oms_channel_inventory"));assertEquals(100,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));
    }
    @Test void validityPauseResumeAndExpiryNeverClearTheLastQuota(){
        stock("A",100);AllocationDraft d=draft(100,100);d.setStartTime(LocalDateTime.now().plusMinutes(5));long id=create(d);daily.tick("VERIFY",id);assertEquals(0,daily.runs("VERIFY",id,1,20).getTotal());assertThrows(IllegalArgumentException.class,()->daily.command("VERIFY",id,revision(id),"RUN","tester"));
        db.update("UPDATE rule_stock_info SET start_time=NOW(),next_run_at=NOW() WHERE id=?",id);daily.tick("VERIFY",id);assertEquals(100,scalar("SELECT available_stock FROM oms_channel_inventory"));
        daily.command("VERIFY",id,revision(id),"PAUSE","tester");due(id);daily.tick("VERIFY",id);assertEquals(1,daily.runs("VERIFY",id,1,20).getTotal());assertEquals(100,scalar("SELECT available_stock FROM oms_channel_inventory"));daily.command("VERIFY",id,revision(id),"ENABLE","tester");daily.tick("VERIFY",id);assertEquals(2,daily.runs("VERIFY",id,1,20).getTotal());
        db.update("UPDATE rule_stock_info SET end_time=NOW() WHERE id=?",id);daily.tick("VERIFY",id);assertEquals(13,scalar("SELECT status FROM rule_stock_info WHERE id=?",id));assertEquals(0,scalar("SELECT daily_enabled FROM rule_stock_info WHERE id=?",id));assertEquals(100,scalar("SELECT available_stock FROM oms_channel_inventory"));
    }
    @Test void priorityPreventsRulesFromOverwritingTheSameChannel(){
        stock("A",100);long low=create(draft(100,80)),high=create(draft(10,20));daily.tick("VERIFY",high);daily.tick("VERIFY",low);assertEquals(20,scalar("SELECT available_stock FROM oms_channel_inventory"));
        Map<String,Object> item=(Map<String,Object>)daily.items("VERIFY",low,number(latest(low),"id"),"",1,20).getRows().get(0);Map<String,Object> detail=(Map<String,Object>)item.get("detail");Map<String,Object> channel=(Map<String,Object>)((List<?>)detail.get("channels")).get(0);assertEquals(true,channel.get("skipped"));assertEquals(0,number(item,"allocatedQuantity"));
        daily.command("VERIFY",high,revision(high),"PAUSE","tester");due(low);daily.tick("VERIFY",low);assertEquals(80,scalar("SELECT available_stock FROM oms_channel_inventory"));
    }
    @Test void failedSkuIsAtomicAndNewRoundCanRecoverWithoutDuplicatingSuccess(){
        stock("A",100);AllocationDraft d=draft(100,100);d.setRuleRange(2);long id=workspace.save("VERIFY",d,"tester");workspace.importGoods("VERIFY",id,revision(id),Arrays.asList("A","MISSING"));workspace.submit("VERIFY",id,revision(id),false);daily.command("VERIFY",id,revision(id),"ENABLE","tester");daily.tick("VERIFY",id);
        assertEquals("PARTIAL",latest(id).get("status"));assertEquals(1,number(latest(id),"failed"));assertEquals(1,scalar("SELECT COUNT(*) FROM oms_channel_inventory"));long old=number(latest(id),"id");stock("MISSING",50);due(id);daily.tick("VERIFY",id);assertEquals("SUCCESS",latest(id).get("status"));assertEquals(100,scalar("SELECT available_stock FROM oms_channel_inventory WHERE sku_sn='A'"));assertEquals(50,scalar("SELECT available_stock FROM oms_channel_inventory WHERE sku_sn='MISSING'"));assertEquals(1,daily.items("VERIFY",id,old,"FAILED",1,20).getTotal());
    }
    @Test void concurrentTriggersShareOneRoundAndEachSkuCommitsOnce() throws Exception {
        stock("A",100);stock("B",100);long id=create();ExecutorService pool=Executors.newFixedThreadPool(4);
        try{List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->daily.tick("VERIFY",id)));for(Future<?> f:tasks)f.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(1,daily.runs("VERIFY",id,1,20).getTotal());assertEquals(2,number(latest(id),"success"));assertEquals(2,scalar("SELECT SUM(attempts) FROM rule_stock_daily_item"));assertEquals(200,scalar("SELECT SUM(available_stock) FROM oms_channel_inventory"));
    }
    @Test void stopBetweenChunksPreservesCommittedSkusAndCanResumeWithANewRound(){
        for(int i=0;i<70;i++)stock("A"+i,100);long id=create();daily.tick("VERIFY",id);Map<String,Object> run=latest(id);assertEquals("RUNNING",run.get("status"));long committed=number(run,"success");assertTrue(committed>0 && committed<70);
        daily.command("VERIFY",id,revision(id),"PAUSE","tester");daily.tick("VERIFY",id);assertEquals("STOPPED",latest(id).get("status"));assertEquals(committed,scalar("SELECT COUNT(*) FROM oms_channel_inventory"));daily.command("VERIFY",id,revision(id),"ENABLE","tester");daily.tick("VERIFY",id);daily.tick("VERIFY",id);assertEquals("SUCCESS",latest(id).get("status"));assertEquals(70,number(latest(id),"success"));assertEquals(2,daily.runs("VERIFY",id,1,20).getTotal());
    }
    @Test void expiryDuringRoundAndTenantGuardsCoverEveryEndpoint(){
        for(int i=0;i<60;i++)stock("A"+i,100);long id=create();daily.tick("VERIFY",id);long run=number(latest(id),"id"),committed=number(latest(id),"success");db.update("UPDATE rule_stock_info SET end_time=NOW() WHERE id=?",id);daily.tick("VERIFY",id);assertEquals("EXPIRED",latest(id).get("status"));assertEquals(committed,scalar("SELECT COUNT(*) FROM oms_channel_inventory"));
        assertThrows(IllegalArgumentException.class,()->daily.runs("OTHER",id,1,20));assertThrows(IllegalArgumentException.class,()->daily.items("OTHER",id,run,"",1,20));assertThrows(IllegalArgumentException.class,()->daily.command("OTHER",id,revision(id),"ENABLE","tester"));assertThrows(IllegalArgumentException.class,()->daily.items("VERIFY",id,run+1000,"",1,20));
    }
    @Test void invalidDailyLockingAndOneTimeBypassesAreRejected(){
        AllocationDraft d=draft(100,100);d.setAllocationType(2);assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",d,"tester"));d.setAllocationType(1);d.setEndTime(d.getStartTime());assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",d,"tester"));
        stock("A",100);long id=create();assertThrows(IllegalArgumentException.class,()->workspace.start("VERIFY",id,revision(id),"EXECUTE","tester"));assertThrows(IllegalArgumentException.class,()->workspace.step("VERIFY",id,"tester"));assertThrows(IllegalArgumentException.class,()->daily.command("VERIFY",id,revision(id)-1,"PAUSE","tester"));
    }
    @Test void explicitLockRuleTypeUsesExistingAtomicReservationFlow(){
        stock("A",100);AllocationDraft d=draft(100,50);d.setRuleType(3);d.setAllocationType(2);long id=workspace.save("VERIFY",d,"tester");workspace.submit("VERIFY",id,revision(id),false);workspace.start("VERIFY",id,revision(id),"EXECUTE","tester");workspace.step("VERIFY",id,"tester");assertEquals(50,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));assertEquals(3,scalar("SELECT rule_type FROM rule_stock_info WHERE id=?",id));
    }
    @Test void scannerScopesCompanyAndUnchangedRoundsAvoidInventoryUpdates(){
        stock("A",100);long id=create();DailyAllocationScanner scanner=context.getBean(DailyAllocationScanner.class);scanner.poll("OTHER");assertEquals(0,daily.runs("VERIFY",id,1,20).getTotal());scanner.poll("verify");assertEquals("SUCCESS",latest(id).get("status"));long version=scalar("SELECT version FROM oms_channel_inventory");due(id);scanner.poll("VERIFY");assertEquals(2,daily.runs("VERIFY",id,1,20).getTotal());assertEquals(version,scalar("SELECT version FROM oms_channel_inventory"));assertThrows(IllegalArgumentException.class,()->scanner.poll("bad company"));
    }
    @Test void dailySchemaDocumentsEveryNewField(){
        assertEquals(0,scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('rule_stock_daily_run','rule_stock_daily_item') AND column_comment=''"));
        assertEquals(5,scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rule_stock_info' AND column_name IN ('interval_minutes','daily_priority','daily_enabled','next_run_at','active_run_id') AND column_comment<>''"));
    }
}
