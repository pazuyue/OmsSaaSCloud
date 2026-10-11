package com.oms.inventory;

import com.oms.common.model.dto.OrderStockCommand;
import com.oms.inventory.service.impl.OrderStockService;
import com.oms.inventory.service.impl.InventoryMutationService;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real transactions against the disposable order_inventory_test schema only. */
public class OrderStockTest {
    @Test void sameSkuLinesWaitTogetherAndDoNotBlockSmallerOrder(){
        seed(100,0);OrderStockCommand large=command("1200",60,true);
        OrderStockCommand.Line second=new OrderStockCommand.Line();second.setPlatformOid("12001");second.setSkuSn("A");second.setQuantity(60);large.getLines().add(second);
        execute("register",large);execute("reserve",large);
        assertEquals(0,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));assertEquals(100,n("SELECT available_stock FROM oms_inventory"));
        assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_demand WHERE reason_code='SHORTAGE'"));
        execute("register",command("1201",80,true));execute("reserve",command("1201",80,true));assertEquals(80,n("SELECT reserved_stock FROM oms_inventory"));
        assertThrows(IllegalArgumentException.class,()->execute("reserve",command("1200",60,true)));
        WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("A");b.setBatchCode("B2");b.setZpActualNumber(100);b.setCpActualNumber(0);inventory.receive(b,"IN-GROUP");
        execute("reserve",large);execute("reserve",large);assertEquals(120,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand WHERE platform_tid='1200'"));assertEquals(200,n("SELECT reserved_stock FROM oms_inventory"));
    }
    @Test void groupedLinesDistributeLockAndPublicTracesExactlyOnce(){
        locked30();OrderStockCommand c=command("1202",20,false);OrderStockCommand.Line second=new OrderStockCommand.Line();second.setPlatformOid("12021");second.setSkuSn("A");second.setQuantity(20);c.getLines().add(second);
        execute("register",c);execute("reserve",c);assertEquals(0,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_demand WHERE reason_code='PUBLIC_DISABLED'"));
        c.setAllowPublicStock(true);execute("reserve",c);execute("reserve",c);
        assertEquals(30,n("SELECT SUM(quantity) FROM oms_order_stock_source WHERE source_type='LOCK'"));assertEquals(10,n("SELECT SUM(quantity) FROM oms_order_stock_source WHERE source_type='PUBLIC_EXTRA'"));
        assertEquals(0,n("SELECT COUNT(*) FROM oms_order_stock_demand d WHERE reserved_quantity<>(SELECT SUM(quantity) FROM oms_order_stock_source s WHERE s.demand_id=d.id)"));
        assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_event WHERE action='RESERVE'"));assertEquals(2,stock.demands("VERIFY",1,"1202").size());
        c.setAllowPublicStock(false);execute("reserve",c);assertEquals(40,n("SELECT reserved_stock FROM oms_inventory"));
    }
    @Test void demandLookupIsReadOnlyAndCompanyScoped(){
        assertTrue(stock.demands("VERIFY",1,"123").isEmpty());assertEquals(0,n("SELECT COUNT(*) FROM oms_inventory"));assertEquals(0,n("SELECT COUNT(*) FROM oms_channel_inventory"));
        execute("register",command("123",5,false));assertEquals(1,stock.demands("VERIFY",1,"123").size());assertTrue(stock.demands("OTHER",1,"123").isEmpty());assertTrue(stock.demands("VERIFY",2,"123").isEmpty());assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_event"));
    }
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db; static OrderStockService stock; static InventoryMutationService inventory;
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){String url=System.getenv("ORDER_STOCK_TEST_URL");if(url==null||!url.contains("/order_inventory_test?"))throw new IllegalStateException("Disposable schema required");return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean com.oms.inventory.service.impl.rule.AllocationReservationService reservations(){return new com.oms.inventory.service.impl.rule.AllocationReservationService();}
        @Bean OrderStockService stock(){return new OrderStockService();}
        @Bean InventoryMutationService inventory(){return new InventoryMutationService();}
    }
    @BeforeAll static void start(){Assumptions.assumeTrue(System.getenv("ORDER_STOCK_TEST_URL")!=null);context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);stock=context.getBean(OrderStockService.class);inventory=context.getBean(InventoryMutationService.class);}
    @AfterAll static void stop(){if(context!=null)context.close();}
    @BeforeEach void clear(){for(String t:Arrays.asList("oms_order_stock_event","oms_order_stock_source","oms_order_stock_demand","rule_stock_reservation","rule_stock_result","rule_stock_info","oms_channel_inventory","wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))db.update("DELETE FROM "+t);}
    long n(String sql){return db.queryForObject(sql,Long.class);}
    void seed(int quantity,int quota){WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("A");b.setBatchCode("B1");b.setZpActualNumber(quantity);b.setCpActualNumber(0);if(quantity>0)inventory.receive(b,"IN-1");db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,available_stock,stock_scope_json) VALUES ('VERIFY',1,'A',?,?, '[\"W1\"]')",0,0);}
    OrderStockCommand command(String tid,int qty,boolean publicStock){OrderStockCommand c=new OrderStockCommand();c.setChannelId(1);c.setPlatformTid(tid);c.setAllowPublicStock(publicStock);OrderStockCommand.Line l=new OrderStockCommand.Line();l.setPlatformOid(tid);l.setSkuSn("A");l.setQuantity(qty);c.getLines().add(l);return c;}
    void execute(String action,OrderStockCommand c){stock.execute("VERIFY",action,c);}
    void locked30(){seed(100,0);long batch=n("SELECT id FROM wms_inventory_batch");db.update("UPDATE wms_inventory SET zp_available_number=70,zp_lock_number=30");db.update("UPDATE wms_inventory_batch SET zp_available_number=70,zp_lock_number=30");db.update("UPDATE oms_inventory SET available_stock=70,allocated_stock=30");db.update("UPDATE oms_channel_inventory SET allocated_stock=30,available_stock=30");db.update("INSERT INTO rule_stock_info(id,company_code,rule_code,rule_name,enable,rule_type,rule_range,rule_mode,allocation_type,run_action) VALUES (700,'VERIFY','TEST700','test',2,2,1,2,2,'EXECUTE')");db.update("INSERT INTO rule_stock_result(rule_id,company_code,sku_sn,status,allocated_quantity,release_status) VALUES (700,'VERIFY','A','SUCCESS',30,'NONE')");db.update("INSERT INTO rule_stock_reservation(company_code,rule_id,sku_sn,channel_id,batch_id,original_quantity) VALUES ('VERIFY',700,'A',1,?,30)",batch);}
    @Test void registerThenReserveDeductsChannelOnceAndNeverChangesWarehouse(){seed(100,20);OrderStockCommand c=command("1001",5,true);execute("register",c);assertEquals(0,n("SELECT available_stock FROM oms_channel_inventory"));assertEquals(5,n("SELECT pending_public_stock FROM oms_inventory"));assertEquals(100,n("SELECT available_stock FROM oms_inventory"));execute("reserve",c);execute("register",c);execute("reserve",c);assertEquals(95,n("SELECT available_stock FROM oms_inventory"));assertEquals(5,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(0,n("SELECT available_stock FROM oms_channel_inventory"));assertEquals(0,n("SELECT pending_stock FROM oms_channel_inventory"));assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_event"));}
    @Test void lock30Order40WaitsWhenPublicDisabledThenUses30Plus10WhenEnabled(){
        locked30();OrderStockCommand c=command("1002",40,false);execute("register",c);execute("reserve",c);
        assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(0,n("SELECT occupied_quantity FROM rule_stock_reservation"));
        assertEquals(70,n("SELECT available_stock FROM oms_inventory"));assertEquals(30,n("SELECT allocated_stock FROM oms_inventory"));
        assertEquals(10,n("SELECT pending_public_stock FROM oms_inventory"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_stock_source"));
        c.setAllowPublicStock(true);execute("reserve",c);execute("reserve",c);
        assertEquals(40,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(30,n("SELECT occupied_quantity FROM rule_stock_reservation"));
        assertEquals(60,n("SELECT available_stock FROM oms_inventory"));assertEquals(0,n("SELECT allocated_stock FROM oms_inventory"));assertEquals(40,n("SELECT reserved_stock FROM oms_inventory"));
        assertEquals(40,n("SELECT reserved_stock FROM oms_channel_inventory"));assertEquals(0,n("SELECT allocated_stock FROM oms_channel_inventory"));
        assertEquals(30,n("SELECT SUM(quantity) FROM oms_order_stock_source WHERE source_type='LOCK'"));assertEquals(10,n("SELECT SUM(quantity) FROM oms_order_stock_source WHERE source_type='PUBLIC_EXTRA'"));
        assertEquals(70,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(30,n("SELECT zp_lock_number FROM wms_inventory"));
        assertEquals(10,OrderStockService.publicReserved(db,"VERIFY","A"));
    }
    @Test void insufficientLargeLineDoesNotHoldChannelLocksNeededBySmallLine(){
        locked30();OrderStockCommand large=command("1020",120,true);execute("register",large);execute("reserve",large);
        OrderStockCommand small=command("1021",20,false);execute("register",small);execute("reserve",small);
        assertEquals(20,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(10,n("SELECT allocated_stock FROM oms_inventory"));assertEquals(70,n("SELECT available_stock FROM oms_inventory"));
        assertEquals(110,n("SELECT pending_public_stock FROM oms_inventory"));assertEquals(120,n("SELECT pending_stock FROM oms_channel_inventory"));
        assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='1020'"));
    }
    @Test void oneChannelCannotUseAnotherChannelsLocks(){
        locked30();OrderStockCommand other=command("1022",10,false);other.setChannelId(2);execute("register",other);execute("reserve",other);
        assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(0,n("SELECT occupied_quantity FROM rule_stock_reservation"));
    }
    @Test void concurrentOrdersCannotUseSameChannelLocksTwice() throws Exception {
        locked30();OrderStockCommand a=command("1023",20,false),b=command("1024",20,false);execute("register",a);execute("register",b);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<?>> jobs=new ArrayList<>();for(OrderStockCommand c:Arrays.asList(a,b))jobs.add(pool.submit(()->{try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}execute("reserve",c);}));start.countDown();for(Future<?> f:jobs)f.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(20,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));assertEquals(20,n("SELECT occupied_quantity FROM rule_stock_reservation"));assertEquals(10,n("SELECT allocated_stock FROM oms_inventory"));
    }
    @Test void releasingLockRulePreservesOrderOccupiedPart(){
        locked30();OrderStockCommand c=command("1025",20,false);execute("register",c);execute("reserve",c);
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
            Map<String,Object> result=db.queryForObject("SELECT * FROM rule_stock_result WHERE rule_id=700",com.oms.inventory.service.impl.InventoryQueryService.ROW);
            context.getBean(com.oms.inventory.service.impl.rule.AllocationReservationService.class).release("VERIFY",700,result,"test");return null;
        });
        assertEquals(20,n("SELECT occupied_quantity FROM rule_stock_reservation"));assertEquals(10,n("SELECT released_quantity FROM rule_stock_reservation"));
        assertEquals(20,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(80,n("SELECT zp_available_number FROM wms_inventory"));
        assertEquals(20,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(0,n("SELECT allocated_stock FROM oms_inventory"));assertEquals(80,n("SELECT available_stock FROM oms_inventory"));
    }
    @Test void inconsistentChannelBalanceRollsBackWholeReservation(){
        locked30();OrderStockCommand c=command("1026",20,false);execute("register",c);db.update("UPDATE oms_channel_inventory SET allocated_stock=0");
        assertThrows(IllegalArgumentException.class,()->execute("reserve",c));
        assertEquals(30,n("SELECT allocated_stock FROM oms_inventory"));assertEquals(0,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(0,n("SELECT occupied_quantity FROM rule_stock_reservation"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_stock_source"));
    }
    @Test void shortageKeepsDemandAndReusesItAfterReceipt(){seed(0,20);OrderStockCommand c=command("1004",5,true);execute("register",c);execute("reserve",c);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_demand"));assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand"));WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("A");b.setBatchCode("B2");b.setZpActualNumber(10);b.setCpActualNumber(0);inventory.receive(b,"IN-2");execute("reserve",c);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_demand"));assertEquals(5,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(0,n("SELECT available_stock FROM oms_channel_inventory"));}
    @Test void overlappingShopsCannotReserveSamePublicStock(){seed(10,10);db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,available_stock,stock_scope_json) VALUES ('VERIFY',2,'A',10,10,'[\"W1\"]')");OrderStockCommand a=command("1005",7,true),b=command("1006",7,true);b.setChannelId(2);execute("register",a);execute("register",b);execute("reserve",b);assertEquals(7,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='1006'"));execute("reserve",a);assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='1005'"));assertEquals(7,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));assertEquals(3,n("SELECT available_stock FROM oms_inventory"));assertEquals(0,n("SELECT SUM(available_stock) FROM oms_channel_inventory"));}
    @Test void duplicateConcurrentCallsOnlyReserveOnce() throws Exception {seed(100,20);OrderStockCommand c=command("1007",5,true);ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->{execute("register",c);execute("reserve",c);}));for(Future<?> f:jobs)f.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_demand"));assertEquals(5,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_event"));}
    @Test void publicStockSkipsLargeDemandButKeepsSellableProtection(){
        seed(100,0);OrderStockCommand large=command("1100",120,true),small=command("1101",80,true),tiny=command("1102",10,true);
        execute("register",large);execute("reserve",large);
        execute("register",small);execute("reserve",small);execute("register",tiny);execute("reserve",tiny);
        assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='1100'"));
        assertEquals(80,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='1101'"));
        assertEquals(10,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='1102'"));
        assertEquals(10,n("SELECT available_stock FROM oms_inventory"));assertEquals(90,n("SELECT reserved_stock FROM oms_inventory"));
        assertEquals(120,n("SELECT pending_public_stock FROM oms_inventory"));assertEquals(120,n("SELECT pending_stock FROM oms_channel_inventory"));
        assertEquals(0,n("SELECT available_stock FROM oms_channel_inventory"));assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));
        execute("reserve",small);assertEquals(90,n("SELECT reserved_stock FROM oms_inventory"));
        WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("A");b.setBatchCode("B2");b.setZpActualNumber(110);b.setCpActualNumber(0);inventory.receive(b,"IN-FIT");
        execute("reserve",large);assertEquals(120,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='1100'"));
        assertEquals(0,n("SELECT available_stock FROM oms_inventory"));assertEquals(210,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(0,n("SELECT pending_public_stock FROM oms_inventory"));
    }
    @Test void concurrentSmallOrdersCannotOverbookWhenLargeDemandIsSkipped() throws Exception {
        seed(100,0);OrderStockCommand large=command("1200",120,true),a=command("1201",80,true),b=command("1202",80,true);b.setChannelId(2);
        execute("register",large);execute("register",a);execute("register",b);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<?>> jobs=new ArrayList<>();for(OrderStockCommand c:Arrays.asList(a,b))jobs.add(pool.submit(()->{try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}execute("reserve",c);}));start.countDown();for(Future<?> f:jobs)f.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(80,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));assertEquals(20,n("SELECT available_stock FROM oms_inventory"));
        assertEquals(80,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(200,n("SELECT pending_public_stock FROM oms_inventory"));
        assertEquals(0,n("SELECT SUM(available_stock) FROM oms_channel_inventory"));assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));
    }
    @Test void changedDemandRejectedAndCompanyCannotBorrowAnotherCompanyStock(){
        seed(100,0);OrderStockCommand c=command("1008",5,true);execute("register",c);c.getLines().get(0).setQuantity(6);
        assertThrows(IllegalArgumentException.class,()->execute("register",c));
        OrderStockCommand other=command("1009",10,true);stock.execute("OTHER","register",other);stock.execute("OTHER","reserve",other);
        assertEquals(0,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));
        assertEquals(100,n("SELECT available_stock FROM oms_inventory WHERE company_code='VERIFY'"));
    }
    @Test void negativeAdjustmentCannotRemoveReservedBacking(){
        seed(100,0);OrderStockCommand c=command("1012",80,true);execute("register",c);execute("reserve",c);
        long id=n("SELECT id FROM wms_inventory_batch");int version=(int)n("SELECT version FROM wms_inventory_batch");
        assertThrows(IllegalArgumentException.class,()->inventory.adjust("VERIFY",id,version,-30,"ZP","test adjust","ORDER-ADJUST-0001"));
        assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(20,n("SELECT available_stock FROM oms_inventory"));
        inventory.adjust("VERIFY",id,version,-20,"ZP","test adjust","ORDER-ADJUST-0002");
        assertEquals(80,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(0,n("SELECT available_stock FROM oms_inventory"));
    }
    @Test void defectiveStockCannotBackGoodStockReservations(){
        WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("A");b.setBatchCode("CP");b.setZpActualNumber(3);b.setCpActualNumber(100);inventory.receive(b,"IN-CP");
        OrderStockCommand c=command("1010",5,true);execute("register",c);execute("reserve",c);
        assertEquals(103,n("SELECT available_stock FROM oms_inventory"));assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand"));
        c=command("1011",3,true);execute("register",c);execute("reserve",c);
        assertEquals(100,n("SELECT available_stock FROM oms_inventory"));assertEquals(3,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));
    }

    @Test void cancelMixedSourcesRestoresOriginalPoolsOnce(){
        locked30();OrderStockCommand c=command("8001",40,true);execute("register",c);execute("reserve",c);execute("cancel",c);execute("cancel",c);
        assertEquals(70,n("SELECT available_stock FROM oms_inventory"));assertEquals(30,n("SELECT allocated_stock FROM oms_inventory"));assertEquals(0,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(30,n("SELECT allocated_stock FROM oms_channel_inventory"));assertEquals(0,n("SELECT pending_stock FROM oms_channel_inventory"));assertEquals(0,n("SELECT reserved_stock FROM oms_channel_inventory"));assertEquals(0,n("SELECT occupied_quantity FROM rule_stock_reservation"));assertEquals(0,n("SELECT occupied_quantity FROM rule_stock_result"));assertEquals(30,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(100,n("SELECT zp_actual_number FROM wms_inventory"));assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_event WHERE action='CANCEL'"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_stock_source WHERE reserved=1"));assertEquals(40,n("SELECT SUM(quantity) FROM oms_order_stock_source"));
        assertThrows(IllegalArgumentException.class,()->execute("reserve",c));assertThrows(IllegalArgumentException.class,()->execute("register",c));assertEquals(0,((Number)stock.execute("VERIFY","query",c).get(0).get("pending_quantity")).longValue());
    }
    @Test void cancelShortageRemovesDemandProtectionWithoutChangingStock(){
        seed(100,0);OrderStockCommand c=command("8002",120,true);execute("register",c);execute("reserve",c);execute("cancel",c);
        assertEquals(100,n("SELECT available_stock FROM oms_inventory"));assertEquals(0,n("SELECT pending_public_stock FROM oms_inventory"));assertEquals(0,n("SELECT pending_stock FROM oms_channel_inventory"));assertEquals(120,n("SELECT demand_quantity FROM oms_order_stock_demand"));assertEquals("CANCELLED",db.queryForObject("SELECT status FROM oms_order_stock_demand",String.class));
    }
    @Test void cancellationAfterRuleReleaseUnlocksReturnedOccupiedStock(){
        locked30();OrderStockCommand c=command("8003",20,false);execute("register",c);execute("reserve",c);
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{Map<String,Object> result=db.queryForObject("SELECT * FROM rule_stock_result WHERE rule_id=700",com.oms.inventory.service.impl.InventoryQueryService.ROW);context.getBean(com.oms.inventory.service.impl.rule.AllocationReservationService.class).release("VERIFY",700,result,"test");return null;});
        execute("cancel",c);execute("cancel",c);assertEquals(100,n("SELECT available_stock FROM oms_inventory"));assertEquals(0,n("SELECT allocated_stock FROM oms_inventory"));assertEquals(0,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(0,n("SELECT allocated_stock FROM oms_channel_inventory"));assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(0,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(30,n("SELECT released_quantity FROM rule_stock_reservation"));assertEquals("RELEASED",db.queryForObject("SELECT release_status FROM rule_stock_result",String.class));
    }
    @Test void cancellationRollbackPreservesAllBalancesOnCorruptSource(){
        locked30();OrderStockCommand c=command("8004",40,true);execute("register",c);execute("reserve",c);db.update("UPDATE oms_channel_inventory SET reserved_stock=0");
        assertThrows(IllegalArgumentException.class,()->execute("cancel",c));assertEquals(40,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(30,n("SELECT occupied_quantity FROM rule_stock_reservation"));assertEquals(40,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(60,n("SELECT available_stock FROM oms_inventory"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_stock_event WHERE action='CANCEL'"));
    }
    @Test void concurrentCancellationAndLateReserveCannotResurrectDemand() throws Exception {
        seed(100,0);OrderStockCommand c=command("8005",40,true);execute("register",c);ExecutorService pool=Executors.newFixedThreadPool(3);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<?>> jobs=new ArrayList<>();for(String action:Arrays.asList("reserve","cancel","cancel"))jobs.add(pool.submit(()->{try{start.await();execute(action,c);}catch(IllegalArgumentException e){if(!"reserve".equals(action))throw e;}catch(InterruptedException e){throw new RuntimeException(e);}}));start.countDown();for(Future<?> f:jobs)f.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(100,n("SELECT available_stock FROM oms_inventory"));assertEquals(0,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(0,n("SELECT pending_stock FROM oms_channel_inventory"));assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_event WHERE action='CANCEL'"));assertEquals("CANCELLED",db.queryForObject("SELECT status FROM oms_order_stock_demand",String.class));
    }
    @Test void cancellationRequiresCompleteOriginalDemand(){
        seed(100,0);OrderStockCommand c=command("8006",10,true);OrderStockCommand.Line second=new OrderStockCommand.Line();second.setPlatformOid("80061");second.setSkuSn("A");second.setQuantity(10);c.getLines().add(second);execute("register",c);execute("reserve",c);
        assertThrows(IllegalArgumentException.class,()->execute("cancel",command("8006",10,true)));assertEquals(20,n("SELECT reserved_stock FROM oms_inventory"));assertThrows(IllegalArgumentException.class,()->stock.execute("OTHER","cancel",c));execute("cancel",c);assertEquals(100,n("SELECT available_stock FROM oms_inventory"));assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_event WHERE action='CANCEL'"));
    }
}
