package com.oms.inventory;

import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.model.dto.ReservationCommand;
import com.oms.inventory.service.impl.rule.AllocationReservationService;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.InventoryMutationService;
import com.oms.inventory.service.impl.rule.AllocationWorkspaceService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL transactions; only docker/local/test_inventory.py's disposable schema is accepted. */
public class AllocationWorkspaceTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db;
    static InventoryMutationService inventory;
    static AllocationWorkspaceService service;
    static AllocationReservationService reservations;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            String url=System.getenv("INVENTORY_TEST_URL");
            if(url==null || !url.contains("/inventory_workspace_test?"))throw new IllegalStateException("Disposable schema required");
            return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean InventoryMutationService inventory(){return new InventoryMutationService();}
        @Bean AllocationWorkspaceService allocations(){return new AllocationWorkspaceService();}
        @Bean AllocationReservationService reservations(){return new AllocationReservationService();}
    }
    @BeforeAll static void init(){
        Assumptions.assumeTrue(System.getenv("INVENTORY_TEST_URL")!=null);
        context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);inventory=context.getBean(InventoryMutationService.class);service=context.getBean(AllocationWorkspaceService.class);
        reservations=context.getBean(AllocationReservationService.class);
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @BeforeEach void clear(){for(String table:Arrays.asList("rule_stock_reservation_event","rule_stock_order_reservation","rule_stock_reservation","rule_stock_result","rule_stock_channel_info","rule_stock_store_code_info","rule_stock_goods_info","rule_stock_info","oms_channel_inventory","wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))db.update("DELETE FROM "+table);}
    void stock(String sku,int amount){
        WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn(sku);b.setBatchCode("B1");b.setZpActualNumber(amount);b.setCpActualNumber(0);inventory.receive(b,"IN-"+sku);
    }
    AllocationDraft draft(boolean locking,int mode,int... percentages){
        AllocationDraft d=new AllocationDraft();d.setRuleName("分货验证");d.setAllocationType(locking?2:1);d.setRuleRange(1);d.setRuleMode(mode);d.setStores(Arrays.asList("W1"));
        for(int i=0;i<percentages.length;i++){AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(i+1);c.setChannelName("渠道"+(i+1));c.setPercentage(BigDecimal.valueOf(percentages[i]));d.getChannels().add(c);}return d;
    }
    long create(boolean locking,int mode,int... percentages){return service.save("VERIFY",draft(locking,mode,percentages),"tester");}
    int version(long id){return ((Number)service.rule("VERIFY",id,false).get("revision")).intValue();}
    int status(long id){return ((Number)service.rule("VERIFY",id,false).get("status")).intValue();}
    void start(long id){service.submit("VERIFY",id,version(id),false);service.start("VERIFY",id,version(id),"EXECUTE","tester");}
    void finish(long id){for(int i=0;i<100 && (status(id)==4 || status(id)==9);i++)service.step("VERIFY",id,"tester");assertFalse(status(id)==4 || status(id)==9);}
    long scalar(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
    long channel(int id,String sku,String field){return scalar("SELECT "+field+" FROM oms_channel_inventory WHERE channel_id=? AND sku_sn=?",id,sku);}

    ReservationCommand command(String line,String request,int quantity,long channel){ReservationCommand c=new ReservationCommand();c.setSkuSn("A");c.setOrderLine(line);c.setRequestId(request);c.setQuantity(quantity);c.setChannelId(channel);return c;}
    void order(long id,String action,String line,int quantity){reservations.order("VERIFY",id,action,command(line,UUID.randomUUID().toString(),quantity,1),"tester");}
    void release(long id){service.start("VERIFY",id,version(id),"RELEASE","tester");finish(id);}

    @Test void partialUseOnlyReleasesUnoccupiedSourceAndCancellationCanReleaseLater(){
        stock("A",100);long id=create(true,2,100);start(id);finish(id);
        order(id,"OCCUPY","ORDER-1",50);order(id,"CONSUME","ORDER-1",30);release(id);
        assertEquals(11,status(id));assertEquals(70,scalar("SELECT zp_actual_number FROM wms_inventory_batch"));assertEquals(50,scalar("SELECT zp_available_number FROM wms_inventory_batch"));assertEquals(20,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));assertEquals(20,channel(1,"A","allocated_stock"));
        assertEquals(50,scalar("SELECT released_quantity FROM rule_stock_result"));assertEquals(30,scalar("SELECT consumed_quantity FROM rule_stock_result"));assertEquals(20,scalar("SELECT occupied_quantity FROM rule_stock_result"));
        assertThrows(IllegalArgumentException.class,()->order(id,"OCCUPY","NEW-ORDER",1));release(id);assertEquals(50,scalar("SELECT released_quantity FROM rule_stock_result"));
        order(id,"CANCEL","ORDER-1",20);assertEquals(20,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));release(id);
        assertEquals(10,status(id));assertEquals(70,scalar("SELECT released_quantity FROM rule_stock_result"));assertEquals(70,scalar("SELECT available_stock FROM oms_inventory"));assertEquals(0,channel(1,"A","allocated_stock"));
    }
    @Test void callbacksAreIdempotentAndCannotConsumeOtherOrdersOrChannels(){
        stock("A",100);long id=create(true,2,60,40);start(id);finish(id);
        ReservationCommand c=command("O1","request-occupy-0001",30,1);reservations.order("VERIFY",id,"OCCUPY",c,"tester");reservations.order("VERIFY",id,"OCCUPY",c,"tester");assertEquals(30,scalar("SELECT occupied_quantity FROM rule_stock_result"));
        c.setQuantity(31);assertThrows(IllegalArgumentException.class,()->reservations.order("VERIFY",id,"OCCUPY",c,"tester"));
        assertThrows(IllegalArgumentException.class,()->order(id,"CONSUME","O2",1));assertThrows(IllegalArgumentException.class,()->order(id,"CONSUME","O1",31));
        assertThrows(IllegalArgumentException.class,()->reservations.order("VERIFY",id,"CONSUME",command("O1","request-wrong-channel",1,2),"tester"));
        ReservationCommand out=command("O1","request-consume-001",20,1);reservations.order("VERIFY",id,"CONSUME",out,"tester");reservations.order("VERIFY",id,"CONSUME",out,"tester");
        assertEquals(80,scalar("SELECT total_stock FROM oms_inventory"));assertEquals(20,scalar("SELECT consumed_quantity FROM rule_stock_result"));assertEquals(1,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='CONSUME'"));
        assertThrows(IllegalArgumentException.class,()->reservations.order("OTHER",id,"CONSUME",out,"tester"));
    }
    @Test void twoRulesSharingBatchNeverReleaseEachOthersBalance(){
        stock("A",200);long a=create(true,2,50);start(a);finish(a);long b=create(true,2,100);start(b);finish(b);
        order(a,"OCCUPY","A-ORDER",50);order(a,"CONSUME","A-ORDER",30);release(a);
        assertEquals(120,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));assertEquals(120,channel(1,"A","allocated_stock"));
        assertEquals(100,scalar("SELECT original_quantity-consumed_quantity-released_quantity FROM rule_stock_reservation WHERE rule_id=?",b));
        release(b);assertEquals(20,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));assertEquals(150,scalar("SELECT zp_available_number FROM wms_inventory_batch"));
    }
    @Test void consumeAfterReleaseFinishesHeldRemainderWithoutReturningShippedStock(){
        stock("A",100);long id=create(true,2,100);start(id);finish(id);order(id,"OCCUPY","O1",100);release(id);assertEquals(11,status(id));
        order(id,"CONSUME","O1",100);assertEquals(10,status(id));assertEquals(0,scalar("SELECT released_quantity FROM rule_stock_result"));assertEquals(0,scalar("SELECT total_stock FROM oms_inventory"));
    }
    @Test void missingSourcesAndGenericReleaseCannotBypassAttribution(){
        stock("A",100);long id=create(true,2,100);start(id);finish(id);
        assertThrows(IllegalArgumentException.class,()->inventory.reserve("VERIFY",Arrays.asList("W1"),"A",BigDecimal.valueOf(100),"RULE-"+id,true));
        db.update("DELETE FROM rule_stock_reservation");release(id);assertEquals(8,status(id));assertEquals(100,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));
        assertThrows(IllegalArgumentException.class,()->order(id,"OCCUPY","O1",1));
    }
    @Test void sourceMismatchRollsBackConsumptionAndCanRetrySameRequest(){
        stock("A",100);long id=create(true,2,100);start(id);finish(id);order(id,"OCCUPY","O1",30);db.update("UPDATE oms_channel_inventory SET allocated_stock=80");
        ReservationCommand c=command("O1","request-consume-retry",20,1);assertThrows(IllegalArgumentException.class,()->reservations.order("VERIFY",id,"CONSUME",c,"tester"));
        assertEquals(100,scalar("SELECT total_stock FROM oms_inventory"));assertEquals(0,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='CONSUME'"));assertEquals(30,scalar("SELECT occupied_quantity FROM rule_stock_result"));
        db.update("UPDATE oms_channel_inventory SET allocated_stock=100");reservations.order("VERIFY",id,"CONSUME",c,"tester");assertEquals(80,scalar("SELECT total_stock FROM oms_inventory"));
    }
    @Test void releaseAndOrderUseSerializeWithoutDeadlockOrDoubleSettlement() throws Exception {
        stock("A",100);long id=create(true,2,100);start(id);finish(id);order(id,"OCCUPY","O1",50);
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {Future<?> a=pool.submit(()->release(id));Future<?> b=pool.submit(()->order(id,"CONSUME","O1",30));Future<?> c=pool.submit(()->order(id,"CANCEL","O1",20));a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);c.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        if(status(id)!=10)release(id);assertEquals(70,scalar("SELECT available_stock FROM oms_inventory"));assertEquals(0,scalar("SELECT allocated_stock FROM oms_inventory"));assertEquals(30,scalar("SELECT consumed_quantity FROM rule_stock_result"));assertEquals(70,scalar("SELECT released_quantity FROM rule_stock_result"));
    }
    @Test void multiBatchChannelAttributionAndListBalancesRemainAccurate(){
        stock("A",40);WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W2");b.setSkuSn("A");b.setBatchCode("B2");b.setZpActualNumber(60);b.setCpActualNumber(0);inventory.receive(b,"IN-A-2");
        AllocationDraft d=draft(true,2,60,40);d.setStores(Arrays.asList("W2","W1"));long id=service.save("VERIFY",d,"tester");start(id);finish(id);
        order(id,"OCCUPY","O1",50);order(id,"CONSUME","O1",45);release(id);
        assertEquals(3,scalar("SELECT COUNT(*) FROM rule_stock_reservation"));assertEquals(5,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));assertEquals(55,scalar("SELECT SUM(zp_actual_number) FROM wms_inventory_batch"));assertEquals(50,scalar("SELECT SUM(zp_available_number) FROM wms_inventory_batch"));
        Map<?,?> row=(Map<?,?>)service.list("VERIFY","",11,2,1,20).getRows().get(0);Map<?,?> balance=(Map<?,?>)row.get("reservationBalance");
        assertEquals(50L,((Number)balance.get("releasedQuantity")).longValue());assertEquals(5L,((Number)balance.get("occupiedQuantity")).longValue());assertEquals(0L,((Number)balance.get("releasableQuantity")).longValue());
        assertEquals(0,service.list("OTHER","",null,null,1,20).getTotal());
    }

    @Test void unchangedFirstChannelStillConsumesQuotaAndZeroClearsOldValues(){
        stock("A",100);db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,available_stock) VALUES ('VERIFY',1,'A',80),('VERIFY',2,'A',70),('VERIFY',3,'A',50)");
        long id=create(false,2,80,80,80);start(id);finish(id);
        assertEquals(5,status(id));assertEquals(80,channel(1,"A","available_stock"));assertEquals(20,channel(2,"A","available_stock"));assertEquals(0,channel(3,"A","available_stock"));assertEquals(100,scalar("SELECT SUM(zp_available_number) FROM wms_inventory"));
    }
    @Test void savedOrderControlsPriorityAndPreviewIsReadOnly(){
        stock("A",100);AllocationDraft d=draft(false,2,80,80);Collections.reverse(d.getChannels());long id=service.save("VERIFY",d,"tester");
        Map<?,?> p=(Map<?,?>)service.preview("VERIFY",id,version(id),1,20).getRows().get(0);
        assertEquals(0,scalar("SELECT COUNT(*) FROM oms_channel_inventory"));assertEquals(0,scalar("SELECT COUNT(*) FROM rule_stock_result"));assertEquals(100L,((Number)p.get("total")).longValue());
        start(id);finish(id);assertEquals(80,channel(2,"A","available_stock"));assertEquals(20,channel(1,"A","available_stock"));
    }
    @Test void lockingAndReleaseAreAtomicAndCannotDuplicate(){
        stock("A",100);long id=create(true,2,60,40);start(id);service.start("VERIFY",id,version(id),"EXECUTE","tester");finish(id);
        assertEquals(100,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));assertEquals(100,scalar("SELECT SUM(allocated_stock) FROM oms_channel_inventory"));
        assertThrows(IllegalArgumentException.class,()->service.step("VERIFY",id,"tester"));service.start("VERIFY",id,version(id),"RELEASE","tester");finish(id);
        assertEquals(10,status(id));assertEquals(100,scalar("SELECT SUM(zp_available_number) FROM wms_inventory_batch"));assertEquals(0,scalar("SELECT SUM(allocated_stock) FROM oms_channel_inventory"));
        assertThrows(IllegalArgumentException.class,()->service.start("VERIFY",id,version(id),"RELEASE","tester"));assertEquals(1,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='LOCK'"));assertEquals(1,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='UNLOCK'"));
    }
    @Test void failedSkuRollsBackAndRetrySkipsSuccessfulSku(){
        stock("A",100);stock("B",100);db.update("UPDATE wms_inventory_batch SET zp_actual_number=90,zp_available_number=90 WHERE sku_sn='B'");
        long id=create(true,2,50);start(id);finish(id);assertEquals(7,status(id));assertEquals(50,channel(1,"A","allocated_stock"));assertEquals(0,scalar("SELECT COUNT(*) FROM oms_channel_inventory WHERE sku_sn='B'"));assertEquals(0,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch WHERE sku_sn='B'"));
        db.update("UPDATE wms_inventory_batch SET zp_actual_number=100,zp_available_number=100 WHERE sku_sn='B'");service.start("VERIFY",id,version(id),"RETRY","tester");finish(id);
        assertEquals(5,status(id));assertEquals(50,channel(1,"A","allocated_stock"));assertEquals(50,channel(1,"B","allocated_stock"));assertEquals(1,scalar("SELECT attempts FROM rule_stock_result WHERE rule_id=? AND sku_sn='A'",id));
    }
    @Test void releaseFailureRollsBackStockAndHistory(){
        stock("A",100);long id=create(true,2,100);start(id);finish(id);db.update("UPDATE oms_channel_inventory SET allocated_stock=1");service.start("VERIFY",id,version(id),"RELEASE","tester");finish(id);
        assertEquals(8,status(id));assertEquals(100,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));assertEquals(0,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='UNLOCK'"));
        db.update("UPDATE oms_channel_inventory SET allocated_stock=100");service.start("VERIFY",id,version(id),"RELEASE","tester");finish(id);assertEquals(10,status(id));
    }
    @Test void tenantStatusRevisionAndImportFailureGuards(){
        AllocationDraft d=draft(false,2,100);d.setRuleRange(2);long id=service.save("VERIFY",d,"tester");assertEquals(2,service.importGoods("VERIFY",id,version(id),Arrays.asList("A","A","B")));
        assertThrows(IllegalArgumentException.class,()->service.importGoods("VERIFY",id,version(id),Arrays.asList("C","")));assertEquals(2,service.goods("VERIFY",id,"",1,20).getTotal());assertEquals(0,service.list("OTHER","",null,null,1,20).getTotal());
        assertThrows(IllegalArgumentException.class,()->service.detail("OTHER",id));assertThrows(IllegalArgumentException.class,()->service.delete("OTHER",id,version(id)));
        d.setId(id);d.setRevision(1);assertThrows(IllegalArgumentException.class,()->service.save("VERIFY",d,"tester"));service.submit("VERIFY",id,version(id),false);
        assertThrows(IllegalArgumentException.class,()->service.importGoods("VERIFY",id,version(id),Arrays.asList("C")));assertThrows(IllegalArgumentException.class,()->service.delete("VERIFY",id,version(id)));
    }
    @Test void independentQuotasMayShareButLocksCannotOversell(){
        stock("A",100);long ordinary=create(false,1,80,80);start(ordinary);finish(ordinary);assertEquals(160,scalar("SELECT SUM(available_stock) FROM oms_channel_inventory"));
        long locking=create(true,1,80,80);start(locking);finish(locking);assertEquals(8,status(locking));assertEquals(0,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));assertEquals(0,scalar("SELECT SUM(allocated_stock) FROM oms_channel_inventory"));
    }
    @Test void zeroStockClearsOrdinaryQuotaWithoutNegativeSales(){
        stock("A",100);db.update("UPDATE wms_inventory SET zp_available_number=0,zp_lock_number=100");db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,available_stock,reserved_stock) VALUES ('VERIFY',1,'A',80,20)");
        long id=create(false,2,100);start(id);finish(id);assertEquals(0,channel(1,"A","available_stock"));
    }
    @Test void concurrentRequestsProcessEachSkuOnce() throws Exception {
        for(String sku:Arrays.asList("A","B","C"))stock(sku,100);long id=create(true,2,50);start(id);ExecutorService pool=Executors.newFixedThreadPool(3);
        try {List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<3;i++)tasks.add(pool.submit(()->{try{service.step("VERIFY",id,"tester");}catch(IllegalArgumentException done){assertEquals(5,status(id));}}));for(Future<?> task:tasks)task.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        finish(id);assertEquals(150,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));assertEquals(3,scalar("SELECT SUM(attempts) FROM rule_stock_result"));
    }
    @Test void competingRulesReadFreshStockAfterLock() throws Exception {
        stock("A",100);long a=create(true,2,80),b=create(true,2,80);start(a);start(b);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {Future<?> x=pool.submit(()->finish(a));Future<?> y=pool.submit(()->finish(b));x.get(30,TimeUnit.SECONDS);y.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(96,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));assertEquals(96,scalar("SELECT SUM(allocated_stock) FROM oms_channel_inventory"));assertEquals(4,scalar("SELECT SUM(zp_available_number) FROM wms_inventory_batch"));
    }
    @Test void executionRecalculatesPreviewAndSkipsAbsentWarehouse(){
        stock("A",100);AllocationDraft d=draft(true,2,100);d.setStores(Arrays.asList("W1","EMPTY"));long id=service.save("VERIFY",d,"tester");
        assertEquals(100L,((Number)((Map<?,?>)service.preview("VERIFY",id,version(id),1,20).getRows().get(0)).get("total")).longValue());inventory.reserve("VERIFY",Arrays.asList("W1"),"A",BigDecimal.valueOf(40),"OTHER",false);
        start(id);finish(id);assertEquals(5,status(id));assertEquals(60,channel(1,"A","allocated_stock"));
    }
    @Test void pageAndStepsStayBoundedOnThousandSkus(){
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        db.update("INSERT INTO wms_inventory(company_code,sku_sn,store_code,zp_actual_number,zp_available_number) SELECT 'VERIFY',CONCAT('P',a.n,b.n,c.n),'W1',100,100 FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c");db.update("INSERT INTO oms_inventory(company_code,sku_sn,total_stock,available_stock) SELECT company_code,sku_sn,100,100 FROM wms_inventory");
        long id=create(false,2,100),begin=System.nanoTime();assertEquals(20,service.preview("VERIFY",id,version(id),1,100).getRows().size());System.out.println("ALLOCATION preview 1000 SKUs page20 ms="+(System.nanoTime()-begin)/1000000.0);
        start(id);service.step("VERIFY",id,"tester");long done=scalar("SELECT COUNT(*) FROM rule_stock_result WHERE status='SUCCESS'");assertTrue(done>0 && done<=10);assertEquals(4,status(id));service.step("VERIFY",id,"tester");assertEquals(1,scalar("SELECT MAX(attempts) FROM rule_stock_result"));assertEquals(1000,service.results("VERIFY",id,"",1,20).getTotal());assertEquals(20,service.results("VERIFY",id,"",1,20).getRows().size());
    }
}
