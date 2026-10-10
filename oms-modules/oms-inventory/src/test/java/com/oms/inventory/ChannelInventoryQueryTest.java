package com.oms.inventory;

import com.oms.inventory.controller.wms.ChannelInventoryController;
import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.model.dto.ReservationCommand;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.*;
import com.oms.inventory.service.impl.rule.*;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.inventory.service.impl.InventoryQueryService.number;

public class ChannelInventoryQueryTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db;
    static ChannelInventoryQueryService queries;
    static InventoryMutationService inventory;
    static AllocationWorkspaceService allocations;
    static AllocationReservationService reservations;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){String url=System.getenv("CHANNEL_INVENTORY_TEST_URL");if(url==null || !url.contains("/channel_inventory_query_test?"))throw new IllegalStateException("Disposable schema required");return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ChannelInventoryQueryService queries(){return new ChannelInventoryQueryService();}
        @Bean InventoryMutationService inventory(){return new InventoryMutationService();}
        @Bean AllocationWorkspaceService allocations(){return new AllocationWorkspaceService();}
        @Bean AllocationReservationService reservations(){return new AllocationReservationService();}
    }
    @BeforeAll static void init(){Assumptions.assumeTrue(System.getenv("CHANNEL_INVENTORY_TEST_URL")!=null);context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);queries=context.getBean(ChannelInventoryQueryService.class);inventory=context.getBean(InventoryMutationService.class);allocations=context.getBean(AllocationWorkspaceService.class);reservations=context.getBean(AllocationReservationService.class);}
    @AfterAll static void close(){if(context!=null)context.close();}
    @BeforeEach void clear(){for(String table:Arrays.asList("rule_stock_daily_item","rule_stock_daily_run","rule_stock_reservation_event","rule_stock_order_reservation","rule_stock_reservation","rule_stock_result","rule_stock_channel_info","rule_stock_store_code_info","rule_stock_goods_info","rule_stock_info","oms_channel_inventory","wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))db.update("DELETE FROM "+table);}
    long scalar(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
    long id(){return scalar("SELECT id FROM oms_channel_inventory WHERE company_code='VERIFY' AND channel_id=1 AND sku_sn='A'");}
    Map<String,Object> first(TableDataInfo page){return (Map<String,Object>)page.getRows().get(0);}
    Map<String,Object> summary(){return (Map<String,Object>)queries.detail("VERIFY",id()).get("reservationSummary");}
    long allocate(boolean lock){
        if(scalar("SELECT COUNT(*) FROM oms_inventory")==0){WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setSkuSn("A");b.setStoreCode("W1");b.setBatchCode("B1");b.setZpActualNumber(100);b.setCpActualNumber(0);inventory.receive(b,"IN-A");}
        AllocationDraft d=new AllocationDraft();d.setRuleName("渠道库存验证");d.setAllocationType(lock?2:1);d.setRuleRange(1);d.setRuleMode(2);d.setStores(Arrays.asList("W1"));AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(1);c.setChannelName("测试渠道");c.setPercentage(BigDecimal.valueOf(50));d.getChannels().add(c);long rule=allocations.save("VERIFY",d,"tester");allocations.submit("VERIFY",rule,1,false);allocations.start("VERIFY",rule,2,"EXECUTE","tester");allocations.step("VERIFY",rule,"tester");return rule;
    }
    void order(long rule,String action,int quantity,String request){ReservationCommand c=new ReservationCommand();c.setSkuSn("A");c.setChannelId(1L);c.setOrderLine("ORDER-1");c.setQuantity(quantity);c.setRequestId(request);reservations.order("VERIFY",rule,action,c,"tester");}

    @Test void allocationOccupationShipmentAndReleaseHaveDistinctBalances(){
        long rule=allocate(true);allocate(false);order(rule,"OCCUPY",30,"occupy-channel-01");order(rule,"CONSUME",10,"consume-channel-01");order(rule,"CANCEL",5,"cancel-channel-01");
        Map<String,Object> row=queries.detail("VERIFY",id());assertEquals(25,number(row,"availableStock"));assertEquals(40,number(row,"allocatedStock"));assertEquals(0,number(row,"reservedStock"));assertEquals(15,number(summary(),"occupiedQuantity"));assertEquals(25,number(summary(),"releasableQuantity"));assertEquals("CONSISTENT",row.get("checkStatus"));
        Map<String,Object> source=first(queries.sources("VERIFY",id(),1,20));assertEquals("B1",source.get("batchCode"));assertEquals(40,number(source,"remainingQuantity"));assertEquals(1,queries.orders("VERIFY",id(),true,1,20).getTotal());assertEquals(3,queries.events("VERIFY",id(),1,20).getTotal());
        allocations.start("VERIFY",rule,((Number)allocations.rule("VERIFY",rule,false).get("revision")).intValue(),"RELEASE","tester");allocations.step("VERIFY",rule,"tester");
        assertEquals(15,number(queries.detail("VERIFY",id()),"allocatedStock"));source=first(queries.sources("VERIFY",id(),1,20));assertEquals(25,number(source,"releasedQuantity"));assertEquals(0,number(source,"releasableQuantity"));assertEquals("PARTIAL",source.get("releaseStatus"));assertEquals(15,number(summary(),"occupiedQuantity"));
    }
    @Test void allDrilldownsRespectCompanyChannelAndSkuIdentity(){
        long rule=allocate(true);order(rule,"OCCUPY",10,"tenant-channel-01");
        // Legacy table keys channel + SKU globally inside each routed company database.
        db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,available_stock) VALUES ('OTHER',3,'A',900),('VERIFY',2,'A',800),('VERIFY',1,'B',700)");
        assertEquals(1,queries.list("VERIFY",1L,"A",false,1,20).getTotal());assertEquals(0,queries.list("VERIFY",null,"A' OR 1=1",false,1,20).getTotal());
        assertThrows(IllegalArgumentException.class,()->queries.detail("OTHER",id()));assertThrows(IllegalArgumentException.class,()->queries.sources("OTHER",id(),1,20));assertThrows(IllegalArgumentException.class,()->queries.orders("OTHER",id(),false,1,20));assertThrows(IllegalArgumentException.class,()->queries.events("OTHER",id(),1,20));assertThrows(IllegalArgumentException.class,()->queries.executions("OTHER",id(),1,20));
        long otherChannel=scalar("SELECT id FROM oms_channel_inventory WHERE company_code='VERIFY' AND channel_id=2");long otherSku=scalar("SELECT id FROM oms_channel_inventory WHERE company_code='VERIFY' AND sku_sn='B'");
        for(long other:new long[]{otherChannel,otherSku}){assertEquals(0,queries.sources("VERIFY",other,1,20).getTotal());assertEquals(0,queries.orders("VERIFY",other,false,1,20).getTotal());assertEquals(0,queries.events("VERIFY",other,1,20).getTotal());assertEquals(0,queries.executions("VERIFY",other,1,20).getTotal());}
    }
    @Test void missingAndInvalidSourcesNeverInventBalances(){
        allocate(true);db.update("UPDATE rule_stock_result SET status='FAILED'");assertEquals("INCOMPLETE",queries.detail("VERIFY",id()).get("checkStatus"));assertNull(first(queries.sources("VERIFY",id(),1,20)).get("remainingQuantity"));assertEquals(0,number(summary(),"trackedLocked"));
        db.update("UPDATE rule_stock_result SET status='SUCCESS'");db.update("UPDATE rule_stock_reservation SET occupied_quantity=60");assertEquals("DIFFERENT",queries.detail("VERIFY",id()).get("checkStatus"));assertNull(first(queries.sources("VERIFY",id(),1,20)).get("releasableQuantity"));
        db.update("UPDATE rule_stock_reservation SET occupied_quantity=0");db.update("UPDATE oms_channel_inventory SET allocated_stock=1");assertEquals("DIFFERENT",queries.detail("VERIFY",id()).get("checkStatus"));
        db.update("UPDATE oms_channel_inventory SET allocated_stock=51");assertEquals(1,new BigDecimal(summary().get("difference").toString()).intValueExact());
    }
    @Test void snapshotsIncludeDailyRoundsAndUseExactJsonChannelMembership(){
        long rule=allocate(false);String snapshot="{\"channels\":[{\"channelId\":1,\"before\":50,\"after\":60,\"change\":10,\"target\":60}]}";
        db.update("INSERT INTO rule_stock_daily_run(company_code,rule_id,status,config_json,operator_name) VALUES ('VERIFY',?,'SUCCESS','{}','tester')",rule);long run=scalar("SELECT id FROM rule_stock_daily_run");
        db.update("INSERT INTO rule_stock_daily_item(run_id,sku_sn,status,detail_json) VALUES (?,'A','SUCCESS',?)",run,snapshot);
        assertEquals(2,queries.executions("VERIFY",id(),1,1).getTotal());assertEquals(1,queries.executions("VERIFY",id(),1,1).getRows().size());
        db.update("UPDATE rule_stock_daily_item SET detail_json=?",snapshot.replace("\"channelId\":1,","\"channelId\":11,"));assertEquals(1,queries.executions("VERIFY",id(),1,20).getTotal());
        db.update("UPDATE rule_stock_daily_item SET detail_json='broken history'");assertEquals(1,queries.executions("VERIFY",id(),1,20).getTotal());
        db.update("UPDATE rule_stock_daily_item SET detail_json=?",snapshot);db.update("UPDATE rule_stock_daily_run SET company_code='OTHER'");assertEquals(1,queries.executions("VERIFY",id(),1,20).getTotal());
    }
    @Test void pagingIsBoundedAndAnyQuantityCountsAsStock(){
        db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,available_stock,allocated_stock,reserved_stock,frozen_stock) VALUES ('VERIFY',1,'A',0,0,0,0),('VERIFY',1,'B',0,1,0,0),('VERIFY',1,'C',0,0,1,0),('VERIFY',1,'D',0,0,0,1)");
        assertEquals(3,queries.list("VERIFY",null,null,true,1,20).getTotal());assertEquals(4,queries.list("VERIFY",null,null,false,1,20).getTotal());assertNotEquals(first(queries.list("VERIFY",null,null,false,1,1)).get("id"),first(queries.list("VERIFY",null,null,false,2,1)).get("id"));
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn) SELECT 'VERIFY',2,CONCAT('P',a.n,b.n,c.n) FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c");assertEquals(100,queries.list("VERIFY",null,null,false,1,1000).getRows().size());
        Map<String,Object> plan=db.queryForMap("EXPLAIN SELECT * FROM oms_channel_inventory FORCE INDEX (idx_channel_company_channel_page) WHERE company_code='VERIFY' AND channel_id=2 ORDER BY id DESC LIMIT 20");assertEquals("idx_channel_company_channel_page",plan.get("key"));assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"));
    }
    @Test void readsDoNotWaitForStockWriteLocks() throws Exception {
        allocate(true);long id=id();CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {Future<?> writer=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(tx->{db.update("UPDATE oms_channel_inventory SET allocated_stock=999 WHERE id=?",id);locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}tx.setRollbackOnly();return null;}));assertTrue(locked.await(5,TimeUnit.SECONDS));Future<Map<String,Object>> reader=pool.submit(()->queries.detail("VERIFY",id));assertEquals(50,number(reader.get(3,TimeUnit.SECONDS),"allocatedStock"));release.countDown();writer.get(5,TimeUnit.SECONDS);}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void everyPublicReadMethodHasItsOwnPermissionAnnotation(){
        Arrays.stream(ChannelInventoryController.class.getDeclaredMethods()).filter(m->m.isAnnotationPresent(GetMapping.class)).forEach(m->{RequiresPermissions p=m.getAnnotation(RequiresPermissions.class);assertNotNull(p,m.getName());assertArrayEquals(new String[]{"channelInventory:inventory:list"},p.value());});
    }
}
