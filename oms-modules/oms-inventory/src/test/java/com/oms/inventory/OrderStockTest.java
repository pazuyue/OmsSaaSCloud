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
    @Test void replacementRoundHasIndependentDemandAndDoesNotReopenCanceledRound(){
        seed(10,0);OrderStockCommand old=command("1700",2,true);old.setPlatformTid("EXCHANGE:17");execute("register",old);execute("reserve",old);execute("cancel",old);
        OrderStockCommand next=command("1700",2,true);next.setPlatformTid("EXCHANGE:17:2");execute("register",next);execute("reserve",next);execute("reserve",next);
        assertEquals(2,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(2,n("SELECT cancelled_quantity FROM oms_order_stock_demand WHERE platform_tid='EXCHANGE:17'"));assertEquals(2,n("SELECT reserved_quantity FROM oms_order_stock_demand WHERE platform_tid='EXCHANGE:17:2'"));assertEquals(2,((Number)stock.demands("VERIFY",1,"EXCHANGE:17").get(0).get("cancelled_quantity")).intValue());assertTrue(stock.demands("OTHER",1,"EXCHANGE:17:2").isEmpty());
        execute("cancel",old);assertEquals(2,n("SELECT reserved_stock FROM oms_inventory"));execute("cancel",next);assertEquals(0,n("SELECT reserved_stock FROM oms_inventory"));
    }
    @Test void damagedExchangeReturnsCannotBecomeSellableLogicalStock(){seed(100,0);WmsInventoryBatch batch=new WmsInventoryBatch();batch.setCompanyCode("VERIFY");batch.setSkuSn("A");batch.setStoreCode("W1");batch.setBatchCode("EXCHANGE_BAD");batch.setZpActualNumber(1);batch.setCpActualNumber(2);inventory.receive(batch,"EXCHANGE_RETURN_TEST");inventory.receive(batch,"EXCHANGE_RETURN_TEST");assertEquals(103,n("SELECT total_stock FROM oms_inventory"));assertEquals(101,n("SELECT available_stock FROM oms_inventory"));assertEquals(2,n("SELECT frozen_stock FROM oms_inventory"));}
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
        @Bean DataSource dataSource(){String url=System.getenv("ORDER_STOCK_TEST_URL");if(url==null||!url.contains("/order_inventory_test?"))throw new IllegalStateException("Disposable schema required");com.alibaba.druid.pool.DruidDataSource pool=new com.alibaba.druid.pool.DruidDataSource();pool.setUrl(url);pool.setUsername("root");pool.setPassword(System.getenv("INVENTORY_TEST_PASSWORD"));pool.setInitialSize(1);pool.setMinIdle(1);pool.setMaxActive(8);pool.setMaxWait(10000);return pool;}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean com.oms.inventory.service.impl.rule.AllocationReservationService reservations(){return new com.oms.inventory.service.impl.rule.AllocationReservationService();}
        @Bean com.oms.inventory.service.impl.OrderVirtualOutboundService virtualOutbound(){return new com.oms.inventory.service.impl.OrderVirtualOutboundService();}
        @Bean com.oms.inventory.service.impl.OrderStockDiagnosticsService diagnostics(){return new com.oms.inventory.service.impl.OrderStockDiagnosticsService();}
        @Bean com.oms.inventory.service.impl.OrderFulfillmentAdjustmentService adjustments(){return new com.oms.inventory.service.impl.OrderFulfillmentAdjustmentService();}
        @Bean com.oms.inventory.service.impl.StockProcessService processes(){return new com.oms.inventory.service.impl.StockProcessService();}
        @Bean OrderStockService stock(){return new OrderStockService();}
        @Bean com.oms.inventory.service.impl.OrderWarehouseAllocationService warehouses(){return new com.oms.inventory.service.impl.OrderWarehouseAllocationService();}
        @Bean InventoryMutationService inventory(){return new InventoryMutationService();}
    }
    @BeforeAll static void start(){Assumptions.assumeTrue(System.getenv("ORDER_STOCK_TEST_URL")!=null);context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);stock=context.getBean(OrderStockService.class);inventory=context.getBean(InventoryMutationService.class);}
    @AfterAll static void stop(){if(context!=null)context.close();}
    @BeforeEach void clear(){for(String t:Arrays.asList("oms_order_fulfillment_adjustment","oms_order_virtual_outbound","oms_order_warehouse_allocation","oms_order_stock_event","oms_order_stock_source","oms_order_stock_demand","rule_stock_reservation","rule_stock_result","rule_stock_info","oms_channel_inventory","wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))db.update("DELETE FROM "+t);}
    long n(String sql){return db.queryForObject(sql,Long.class);}
    void seed(int quantity,int quota){WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("A");b.setBatchCode("B1");b.setZpActualNumber(quantity);b.setCpActualNumber(0);if(quantity>0)inventory.receive(b,"IN-1");db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,available_stock,stock_scope_json) VALUES ('VERIFY',1,'A',?,?, '[\"W1\"]')",quota,Math.min(quantity,quota));}
    OrderStockCommand command(String tid,int qty,boolean publicStock){OrderStockCommand c=new OrderStockCommand();c.setChannelId(1);c.setPlatformTid(tid);c.setAllowPublicStock(publicStock);OrderStockCommand.Line l=new OrderStockCommand.Line();l.setPlatformOid(tid);l.setSkuSn("A");l.setQuantity(qty);c.getLines().add(l);return c;}
    void execute(String action,OrderStockCommand c){stock.execute("VERIFY",action,c);}
    void locked30(){seed(100,0);long batch=n("SELECT id FROM wms_inventory_batch");db.update("UPDATE wms_inventory SET zp_available_number=70,zp_lock_number=30");db.update("UPDATE wms_inventory_batch SET zp_available_number=70,zp_lock_number=30");db.update("UPDATE oms_inventory SET available_stock=70,allocated_stock=30");db.update("UPDATE oms_channel_inventory SET allocated_stock=30,available_stock=30");db.update("INSERT INTO rule_stock_info(id,company_code,rule_code,rule_name,enable,rule_type,rule_range,rule_mode,allocation_type,run_action) VALUES (700,'VERIFY','TEST700','test',2,2,1,2,2,'EXECUTE')");db.update("INSERT INTO rule_stock_result(rule_id,company_code,sku_sn,status,allocated_quantity,release_status) VALUES (700,'VERIFY','A','SUCCESS',30,'NONE')");db.update("INSERT INTO rule_stock_reservation(company_code,rule_id,sku_sn,channel_id,batch_id,original_quantity) VALUES ('VERIFY',700,'A',1,?,30)",batch);}
    @Test void registerThenReserveDeductsChannelOnceAndNeverChangesWarehouse(){seed(100,20);OrderStockCommand c=command("1001",5,true);execute("register",c);assertEquals(15,n("SELECT available_stock FROM oms_channel_inventory"));assertEquals(5,n("SELECT pending_public_stock FROM oms_inventory"));assertEquals(100,n("SELECT available_stock FROM oms_inventory"));execute("reserve",c);execute("register",c);execute("reserve",c);assertEquals(95,n("SELECT available_stock FROM oms_inventory"));assertEquals(5,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(15,n("SELECT available_stock FROM oms_channel_inventory"));assertEquals(5,n("SELECT quota_reserved_stock FROM oms_channel_inventory"));assertEquals(0,n("SELECT pending_stock FROM oms_channel_inventory"));assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_event"));}
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
    @Test void shortageKeepsDemandAndReusesItAfterReceipt(){seed(0,20);OrderStockCommand c=command("1004",5,true);execute("register",c);execute("reserve",c);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_demand"));assertEquals(0,n("SELECT reserved_quantity FROM oms_order_stock_demand"));WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("A");b.setBatchCode("B2");b.setZpActualNumber(10);b.setCpActualNumber(0);inventory.receive(b,"IN-2");execute("reserve",c);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_stock_demand"));assertEquals(5,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(5,n("SELECT available_stock FROM oms_channel_inventory"));}
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
    @Test void reallocationKeepsPublicReservationAndCancellationRestoresQuotaOnce(){
        seed(325,0);db.update("UPDATE oms_channel_inventory SET company_code='verify'");
        OrderStockCommand c=command("8100",5,true);execute("register",c);
        assertEquals(5,n("SELECT pending_stock FROM oms_channel_inventory"));
        execute("reserve",c);assertEquals(5,n("SELECT quota_reserved_stock FROM oms_channel_inventory"));
        db.update("UPDATE oms_channel_inventory SET quota_stock=195,quota_reserved_stock=0");
        execute("query",c);execute("query",c);
        assertEquals(190,n("SELECT available_stock FROM oms_channel_inventory"));
        assertEquals(195,n("SELECT quota_stock FROM oms_channel_inventory"));
        assertEquals(5,n("SELECT quota_reserved_stock FROM oms_channel_inventory"));
        assertEquals(320,n("SELECT available_stock FROM oms_inventory"));
        db.update("UPDATE oms_channel_inventory SET quota_stock=3");execute("query",c);
        assertEquals(0,n("SELECT available_stock FROM oms_channel_inventory"));
        db.update("UPDATE oms_channel_inventory SET quota_stock=195");execute("cancel",c);execute("cancel",c);
        assertEquals(195,n("SELECT available_stock FROM oms_channel_inventory"));
        assertEquals(0,n("SELECT quota_reserved_stock FROM oms_channel_inventory"));
        assertEquals(325,n("SELECT available_stock FROM oms_inventory"));
    }
    @Test void mixedReservationsDeductOnlyPublicPartFromQuota(){
        locked30();db.update("UPDATE oms_channel_inventory SET quota_stock=20");
        OrderStockCommand c=command("8101",40,true);execute("register",c);
        assertEquals(10,n("SELECT available_stock FROM oms_channel_inventory"));
        execute("reserve",c);execute("reserve",c);
        assertEquals(40,n("SELECT reserved_stock FROM oms_channel_inventory"));
        assertEquals(10,n("SELECT quota_reserved_stock FROM oms_channel_inventory"));
        assertEquals(10,n("SELECT available_stock FROM oms_channel_inventory"));
        execute("cancel",c);
        assertEquals(0,n("SELECT quota_reserved_stock FROM oms_channel_inventory"));
        assertEquals(50,n("SELECT available_stock FROM oms_channel_inventory"));
    }
    @Test void anotherChannelsReservationDoesNotConsumeThisChannelsQuota(){
        seed(325,195);
        db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('VERIFY',2,'A',100,'[\"W1\"]')");
        OrderStockCommand c=command("8102",5,true);c.setChannelId(2);execute("register",c);execute("reserve",c);
        assertEquals(195,n("SELECT available_stock FROM oms_channel_inventory WHERE channel_id=1"));
        assertEquals(0,n("SELECT quota_reserved_stock FROM oms_channel_inventory WHERE channel_id=1"));
        assertEquals(95,n("SELECT available_stock FROM oms_channel_inventory WHERE channel_id=2"));
        assertEquals(5,n("SELECT quota_reserved_stock FROM oms_channel_inventory WHERE channel_id=2"));
    }

    Map<String,Object> allocationCommand(String tid,boolean multi,boolean partial,boolean split,String...stores){
        Map<String,Object> c=new LinkedHashMap<>();c.put("channel_id",1);c.put("platform_tid",tid);c.put("allow_multi",multi?1:0);c.put("allow_partial",partial?1:0);c.put("allow_split_sku",split?1:0);
        c.put("lines",db.queryForList("SELECT platform_oid,sku_sn,demand_quantity quantity FROM oms_order_stock_demand WHERE company_code='VERIFY' AND platform_tid=?",tid));
        List<Map<String,Object>> choices=new ArrayList<>();for(String code:stores){Map<String,Object> store=new HashMap<>();store.put("store_code",code);store.put("owner_code","OWNER");store.put("real_store_code",code);store.put("real_store_name",code);choices.add(store);}c.put("stores",choices);
        return c;
    }
    List<Map<String,Object>> allocate(String tid,boolean multi,boolean partial,boolean split,String...stores){return context.getBean(com.oms.inventory.service.impl.OrderWarehouseAllocationService.class).allocate("VERIFY",allocationCommand(tid,multi,partial,split,stores));}
    void receive(String store,String sku,int qty,String key){WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode(store);b.setSkuSn(sku);b.setBatchCode(key);b.setZpActualNumber(qty);b.setCpActualNumber(0);inventory.receive(b,key);}
    @Test void publicWarehouseOccupationRetriesOnceAndCancellationReleasesExactlyOnce(){
        seed(100,20);OrderStockCommand c=command("3001",10,true);execute("register",c);execute("reserve",c);
        assertEquals(1,allocate("3001",false,false,false,"W1").size());allocate("3001",false,false,false,"W1");
        assertEquals(90,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(10,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(0,OrderStockService.publicReserved(db,"VERIFY","A"));assertEquals(90,n("SELECT available_stock FROM oms_inventory"));
        execute("cancel",c);execute("cancel",c);assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(0,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(0,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(1,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation WHERE status='RELEASED'"));
        assertThrows(IllegalArgumentException.class,()->allocate("3001",false,false,false,"W1"));
    }
    @Test void channelLockOccupationIsInheritedAndPublicBalanceIsNotCountedTwice(){
        locked30();OrderStockCommand c=command("3002",40,true);execute("register",c);execute("reserve",c);allocate("3002",false,false,false,"W1");
        assertEquals(60,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(40,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(30,n("SELECT occupied_quantity FROM rule_stock_reservation"));assertEquals(0,OrderStockService.publicReserved(db,"VERIFY","A"));
        execute("cancel",c);assertEquals(70,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(30,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(0,n("SELECT occupied_quantity FROM rule_stock_reservation"));assertEquals(30,n("SELECT allocated_stock FROM oms_inventory"));
    }
    @Test void invalidSourceLedgerDoesNotChangeAnyWarehouse(){
        seed(100,20);OrderStockCommand c=command("3003",10,true);execute("register",c);execute("reserve",c);db.update("DELETE FROM oms_order_stock_source");
        assertThrows(IllegalArgumentException.class,()->allocate("3003",false,false,false,"W1"));assertEquals(100,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));
    }
    @Test void originalLockedWarehouseCannotBeMovedOutsideAllowlist(){
        locked30();OrderStockCommand c=command("3004",20,false);execute("register",c);execute("reserve",c);
        assertThrows(IllegalArgumentException.class,()->allocate("3004",true,true,true,"W2"));assertEquals(30,n("SELECT zp_lock_number FROM wms_inventory"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));
    }
    @Test void sameSkuNeedsExplicitCrossWarehouseSwitch(){
        seed(6,20);receive("W2","A",6,"IN-SPLIT");OrderStockCommand c=command("3005",10,true);execute("register",c);execute("reserve",c);
        assertThrows(IllegalArgumentException.class,()->allocate("3005",true,false,false,"W1","W2"));assertThrows(IllegalArgumentException.class,()->allocate("3005",false,false,true,"W1","W2"));
        assertEquals(0,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));allocate("3005",true,false,true,"W1","W2");assertEquals(10,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));assertEquals(2,n("SELECT COUNT(DISTINCT real_store_code) FROM oms_order_warehouse_allocation"));
    }
    @Test void partialWarehouseAllocationAfterReceiptOnlyAddsRemainingQuantity(){
        seed(6,20);receive("W2","A",6,"IN-PART");OrderStockCommand c=command("3006",10,true);execute("register",c);execute("reserve",c);
        allocate("3006",false,true,false,"W1");assertEquals(6,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));receive("W1","A",4,"IN-PART-REST");allocate("3006",false,true,false,"W1");assertEquals(10,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));
    }
    @Test void partialShippingAllocatesReservedSkuAndLaterRestWithoutDuplicates(){
        seed(20,20);OrderStockCommand c=command("3007",10,true);OrderStockCommand.Line b=new OrderStockCommand.Line();b.setPlatformOid("30071");b.setSkuSn("B");b.setQuantity(5);c.getLines().add(b);execute("register",c);execute("reserve",c);
        assertThrows(IllegalArgumentException.class,()->allocate("3007",false,false,false,"W1"));allocate("3007",false,true,false,"W1");assertEquals(10,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));
        receive("W1","B",5,"IN-REST");execute("reserve",c);allocate("3007",false,true,false,"W1");allocate("3007",false,true,false,"W1");assertEquals(15,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));assertEquals(2,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));
    }
    @Test void warehouseSummaryMismatchRollsBackWholeOrder(){
        seed(20,20);OrderStockCommand c=command("3008",10,true);execute("register",c);execute("reserve",c);db.update("UPDATE wms_inventory_batch SET zp_available_number=19");
        assertThrows(IllegalArgumentException.class,()->allocate("3008",false,false,false,"W1"));assertEquals(20,n("SELECT zp_available_number FROM wms_inventory"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));
    }
    @Test void concurrentWarehouseRetriesCannotOccupyTwice() throws Exception {
        seed(100,20);OrderStockCommand c=command("3009",10,true);execute("register",c);execute("reserve",c);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->allocate("3009",false,false,false,"W1")));for(Future<?> job:jobs)job.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(10,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));assertEquals(90,n("SELECT zp_available_number FROM wms_inventory"));
    }

    Map<String,Object> diagnostic(String tid){Map<String,Object> key=new HashMap<>();key.put("channel_id",1);key.put("platform_tid",tid);return context.getBean(com.oms.inventory.service.impl.OrderStockDiagnosticsService.class).facts("VERIFY",Arrays.asList(key)).get(0);}
    List<Map<String,Object>> diagnosticIssues(String tid){return (List<Map<String,Object>>)((List<Map<String,Object>>)diagnostic(tid).get("sku_facts")).get(0).get("issues");}
    @Test void zeroChannelQuotaWithEnoughPublicStockIsAWarningNotACompanyShortage() throws Exception {
        seed(2,0);OrderStockCommand command=command("99001",2,true);execute("register",command);execute("reserve",command);
        String snapshot=db.queryForObject("SELECT decision_snapshot FROM oms_order_stock_demand",String.class);com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot);assertEquals(2,json.path("company_available").asInt());assertEquals(0,json.path("channel_quota").asInt());assertEquals(2,json.path("public_needed").asInt());
        List<Map<String,Object>> issues=diagnosticIssues("99001");assertEquals(1,issues.size());assertEquals("QUOTA",issues.get(0).get("category"));assertEquals(0,((Number)issues.get(0).get("blocking")).intValue());assertEquals(2,n("SELECT reserved_stock FROM oms_inventory"));assertEquals(2,n("SELECT zp_available_number FROM wms_inventory"));execute("reserve",command);assertEquals(snapshot,db.queryForObject("SELECT decision_snapshot FROM oms_order_stock_demand",String.class));
    }
    @Test void NormalReservationDoesNotBecomeShortageBecauseItsOwnReservationMakesAvailabilityZero(){
        seed(2,2);OrderStockCommand command=command("99002",2,true);execute("register",command);assertEquals(0,n("SELECT available_stock FROM oms_channel_inventory"));execute("reserve",command);assertEquals(0,n("SELECT available_stock FROM oms_inventory"));assertTrue(diagnosticIssues("99002").isEmpty());db.update("UPDATE oms_channel_inventory SET quota_stock=0");assertEquals("QUOTA",diagnosticIssues("99002").get(0).get("category"));db.update("UPDATE oms_channel_inventory SET quota_stock=2");assertTrue(diagnosticIssues("99002").isEmpty());
    }
    @Test void diagnosticsDistinguishRealShortageFromPublicStockRestrictionAndNeverWriteBalances(){
        seed(1,10);OrderStockCommand command=command("99003",2,true);execute("register",command);execute("reserve",command);long events=n("SELECT COUNT(*) FROM oms_order_stock_event");List<Map<String,Object>> issues=diagnosticIssues("99003");assertEquals("SHORTAGE",issues.get(0).get("category"));assertEquals(1,((Number)((Map<?,?>)issues.get(0).get("evidence")).get("shortage_quantity")).intValue());assertEquals(1,n("SELECT available_stock FROM oms_inventory"));assertEquals(events,n("SELECT COUNT(*) FROM oms_order_stock_event"));command.setAllowPublicStock(false);execute("reserve",command);assertEquals("RESTRICTED",diagnosticIssues("99003").get(0).get("category"));
    }
    @Test void diagnosticsGroupSameSkuAndDetectMissingSourceWithoutLeakingOtherCompanies(){
        seed(4,2);OrderStockCommand command=command("99004",2,true);OrderStockCommand.Line line=new OrderStockCommand.Line();line.setPlatformOid("990041");line.setSkuSn("A");line.setQuantity(2);command.getLines().add(line);execute("register",command);execute("reserve",command);assertEquals(1,diagnosticIssues("99004").size());assertEquals(2,((Number)((Map<?,?>)diagnosticIssues("99004").get(0).get("evidence")).get("excess_quantity")).intValue());db.update("DELETE FROM oms_order_stock_source LIMIT 1");assertTrue(diagnosticIssues("99004").stream().anyMatch(issue->"LEDGER".equals(issue.get("category"))));Map<String,Object> key=new HashMap<>();key.put("channel_id",1);key.put("platform_tid","99004");assertTrue(((List<?>)context.getBean(com.oms.inventory.service.impl.OrderStockDiagnosticsService.class).facts("OTHER",Arrays.asList(key)).get(0).get("lines")).isEmpty());
    }
    @Test void queuedDemandIsNotRestrictedWhenItsChannelLocksOrOtherUsableStockAreEnough(){
        locked30();execute("register",command("99005",20,false));assertTrue(diagnosticIssues("99005").isEmpty());clear();seed(100,100);execute("register",command("99006",5,true));db.update("UPDATE oms_inventory SET frozen_stock=10,available_stock=90");assertTrue(diagnosticIssues("99006").isEmpty());
    }

    @Test void warehousePreviewIsReadOnlyAndStalePlanNeverAddsOccupation(){seed(6,20);receive("W2","A",4,"PREVIEW-W2");OrderStockCommand demand=command("4401",10,true);execute("register",demand);execute("reserve",demand);Map<String,Object> command=allocationCommand("4401",true,false,true,"W1","W2");command.put("existing_shipment_count",0);command.put("mapped_allocation_ids",Collections.emptyList());com.oms.inventory.service.impl.OrderWarehouseAllocationService service=context.getBean(com.oms.inventory.service.impl.OrderWarehouseAllocationService.class);Map<String,Object> report=service.preview("VERIFY",command);assertEquals(true,report.get("can_allocate"));assertEquals(10L,report.get("planned_quantity"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));assertEquals(0,n("SELECT SUM(zp_lock_number) FROM wms_inventory"));receive("W2","A",6,"PREVIEW-CHANGED");command.put("expected_plan_fingerprint",report.get("plan_fingerprint"));assertThrows(IllegalArgumentException.class,()->service.allocate("VERIFY",command));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));assertEquals(0,n("SELECT SUM(zp_lock_number) FROM wms_inventory"));command.put("expected_plan_fingerprint",service.preview("VERIFY",command).get("plan_fingerprint"));service.allocate("VERIFY",command);assertEquals(10,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));}
    @Test void previewCanRecoverCommittedInventoryReceiptsWithoutDoubleOccupation(){seed(10,20);OrderStockCommand demand=command("4402",10,true);execute("register",demand);execute("reserve",demand);Map<String,Object> command=allocationCommand("4402",false,false,false,"W1");command.put("max_shipments",1);command.put("existing_shipment_count",0);command.put("mapped_allocation_ids",Collections.emptyList());com.oms.inventory.service.impl.OrderWarehouseAllocationService service=context.getBean(com.oms.inventory.service.impl.OrderWarehouseAllocationService.class);service.allocate("VERIFY",command);Map<String,Object> report=service.preview("VERIFY",command);assertEquals(true,report.get("can_allocate"));assertEquals(0L,report.get("planned_quantity"));assertEquals(10L,report.get("recovered_quantity"));assertEquals(1,report.get("new_shipment_count"));command.put("expected_plan_fingerprint",report.get("plan_fingerprint"));service.allocate("VERIFY",command);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));assertEquals(10,n("SELECT SUM(zp_lock_number) FROM wms_inventory"));}
    static class CountingQueries extends JdbcTemplate{int queries;List<String> statements=new ArrayList<>();CountingQueries(DataSource dataSource){super(dataSource);}@Override public List<Map<String,Object>> queryForList(String sql,Object...args){queries++;statements.add(sql);return super.queryForList(sql,args);}}
    @Test void hundredCandidateWarehousesUseSixBatchReadsWithoutUpdateLocks(){seed(10,20);OrderStockCommand demand=command("4403",10,true);execute("register",demand);execute("reserve",demand);String[] warehouses=new String[100];for(int i=0;i<100;i++)warehouses[i]="W"+(i+1);Map<String,Object> command=allocationCommand("4403",true,true,true,warehouses);CountingQueries queries=new CountingQueries(db.getDataSource());com.oms.inventory.service.impl.OrderWarehouseAllocationService service=new com.oms.inventory.service.impl.OrderWarehouseAllocationService();org.springframework.test.util.ReflectionTestUtils.setField(service,"jdbc",queries);assertEquals(true,service.preview("VERIFY",command).get("can_allocate"));assertEquals(6,queries.queries);assertTrue(queries.statements.stream().noneMatch(sql->sql.contains("FOR UPDATE")));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));}
    @Test void concurrentAllocationOfSameOrderReturnsOneDurableOccupation() throws Exception {seed(10,20);OrderStockCommand demand=command("4404",10,true);execute("register",demand);execute("reserve",demand);Map<String,Object> command=allocationCommand("4404",false,false,false,"W1");com.oms.inventory.service.impl.OrderWarehouseAllocationService service=context.getBean(com.oms.inventory.service.impl.OrderWarehouseAllocationService.class);ExecutorService workers=Executors.newFixedThreadPool(2);try{Future<?> first=workers.submit(()->service.allocate("VERIFY",command));Future<?> second=workers.submit(()->service.allocate("VERIFY",command));first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_warehouse_allocation"));assertEquals(10,n("SELECT SUM(zp_lock_number) FROM wms_inventory"));}finally{workers.shutdownNow();}}
    @Test void exchangeStockLookupRespectsWarehouseScopeAndExistingOwnReservationWithoutWrites(){
        seed(10,10);OrderStockCommand c=command("1700",4,true);c.setPlatformTid("EXCHANGE:17");execute("register",c);execute("reserve",c);
        com.oms.inventory.service.impl.ExchangeStockLookup lookup=new com.oms.inventory.service.impl.ExchangeStockLookup();org.springframework.test.util.ReflectionTestUtils.setField(lookup,"jdbc",db);
        Map<String,Object> input=new HashMap<>();input.put("skus",Arrays.asList("A"));input.put("stores",Arrays.asList("W1"));input.put("channel_id",1);input.put("allow_public_stock",1);input.put("reference","EXCHANGE:17");long version=n("SELECT version FROM oms_inventory"),events=n("SELECT COUNT(*) FROM oms_order_stock_event");
        Map<String,Object> stock=lookup.lookup("VERIFY",input).get(0);assertEquals(4L,stock.get("own_reserved"));assertEquals(10L,stock.get("reference_capacity"));assertEquals(6L,stock.get("company_available"));
        input.put("stores",Arrays.asList("W2"));assertEquals(0L,lookup.lookup("VERIFY",input).get(0).get("reference_capacity"));assertEquals(0L,lookup.lookup("OTHER",input).get(0).get("reference_capacity"));assertEquals(version,n("SELECT version FROM oms_inventory"));assertEquals(events,n("SELECT COUNT(*) FROM oms_order_stock_event"));
        input.put("skus",Collections.nCopies(21,"A"));assertThrows(IllegalArgumentException.class,()->lookup.lookup("VERIFY",input));
    }
    @Test void samePlatformLineHasIndependentComponentDemandAndCancellationIsIdempotent(){seed(10,10);WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("B");b.setBatchCode("B2");b.setZpActualNumber(10);b.setCpActualNumber(0);inventory.receive(b,"IN-B");OrderStockCommand c=command("9001",4,true);OrderStockCommand.Line part=new OrderStockCommand.Line();part.setPlatformOid("9001");part.setSkuSn("B");part.setQuantity(2);c.getLines().add(part);execute("register",c);execute("register",c);execute("reserve",c);execute("reserve",c);assertEquals(2,n("SELECT COUNT(*) FROM oms_order_stock_demand"));assertEquals(6,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));assertEquals(6,n("SELECT SUM(reserved_stock) FROM oms_inventory"));execute("cancel",c);execute("cancel",c);assertEquals(0,n("SELECT SUM(reserved_stock) FROM oms_inventory"));assertEquals(6,n("SELECT SUM(cancelled_quantity) FROM oms_order_stock_demand"));}
    @Test void kitComponentsReserveAvailablePiecesAndReplenishmentOnlyTakesTheRemainder(){seed(3,10);OrderStockCommand c=command("9010",4,true);c.getLines().get(0).setComponentQuantity(2);execute("register",c);execute("reserve",c);execute("reserve",c);assertEquals(3,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(3,n("SELECT SUM(quantity) FROM oms_order_stock_source"));WmsInventoryBatch batch=new WmsInventoryBatch();batch.setCompanyCode("VERIFY");batch.setStoreCode("W1");batch.setSkuSn("A");batch.setBatchCode("REFILL");batch.setZpActualNumber(1);batch.setCpActualNumber(0);inventory.receive(batch,"REFILL-1");execute("reserve",c);execute("reserve",c);assertEquals(4,n("SELECT reserved_quantity FROM oms_order_stock_demand"));assertEquals(4,n("SELECT SUM(quantity) FROM oms_order_stock_source"));assertEquals(4,n("SELECT quantity FROM oms_order_stock_event WHERE action='RESERVE'"));assertEquals(4,n("SELECT reserved_stock FROM oms_inventory"));}
    Map<String,Object> kitMap(Object... args){Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)row.put(args[i].toString(),args[i+1]);return row;}
    @Test void partialKitReservationFeedsWholeSetAllocationAndRefillDoesNotDuplicateOccupation(){seed(4,10);WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W1");b.setSkuSn("B");b.setBatchCode("KIT-B1");b.setZpActualNumber(1);b.setCpActualNumber(0);inventory.receive(b,"KIT-B1");OrderStockCommand c=command("9020",4,true);c.getLines().get(0).setComponentQuantity(2);OrderStockCommand.Line part=new OrderStockCommand.Line();part.setPlatformOid("9020");part.setSkuSn("B");part.setQuantity(2);part.setComponentQuantity(1);c.getLines().add(part);execute("register",c);execute("reserve",c);assertEquals(5,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));Map<String,Object> command=kitMap("channel_id",1,"platform_tid","9020","allow_multi",0,"allow_partial",1,"allow_split_sku",0,"lines",Arrays.asList(kitMap("platform_oid","9020","sku_sn","A","quantity",4,"bundle_id",1,"component_quantity",2),kitMap("platform_oid","9020","sku_sn","B","quantity",2,"bundle_id",1,"component_quantity",1)),"stores",Arrays.asList(kitMap("store_code","W1","owner_code","O","real_store_code","R","real_store_name","R")));com.oms.inventory.service.impl.OrderWarehouseAllocationService wh=context.getBean(com.oms.inventory.service.impl.OrderWarehouseAllocationService.class);assertEquals(3,com.oms.inventory.service.impl.WarehouseAllocationPlanner.quantity(wh.allocate("VERIFY",command)));assertEquals(3,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));b.setBatchCode("KIT-B2");inventory.receive(b,"KIT-B2");execute("reserve",c);assertEquals(6,n("SELECT SUM(reserved_quantity) FROM oms_order_stock_demand"));assertEquals(6,com.oms.inventory.service.impl.WarehouseAllocationPlanner.quantity(wh.allocate("VERIFY",command)));wh.allocate("VERIFY",command);assertEquals(6,n("SELECT SUM(quantity) FROM oms_order_warehouse_allocation"));assertEquals(6,n("SELECT SUM(zp_lock_number) FROM wms_inventory"));}

    Map<String,Object> syncSnapshot(boolean publicStock,List<Map<String,Object>> parts){com.oms.inventory.service.impl.PlatformStockSnapshot service=new com.oms.inventory.service.impl.PlatformStockSnapshot();org.springframework.test.util.ReflectionTestUtils.setField(service,"db",db);Map<String,Object> query=kitMap("channel_id",1,"allow_public_stock",publicStock,"components",parts);return new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(tx->service.snapshot("VERIFY",query));}
    @Test void syncSnapshotUsesCurrentChannelAvailableWithoutDoubleReservationDeduction(){seed(100,20);execute("register",command("99071",5,true));execute("reserve",command("99071",5,true));assertEquals(15L,syncSnapshot(true,Arrays.asList(kitMap("sku_sn","A","quantity",1))).get("available"));assertEquals(95,n("SELECT available_stock FROM oms_inventory"));}
    @Test void syncSnapshotCannotAdvertisePublicStockWhenShopDisallowsIt(){seed(100,20);assertEquals(0L,syncSnapshot(false,Arrays.asList(kitMap("sku_sn","A","quantity",1))).get("available"));}
    @Test void syncSnapshotMissingChannelInventoryIsAnErrorNotZero(){assertThrows(IllegalArgumentException.class,()->syncSnapshot(true,Arrays.asList(kitMap("sku_sn","A","quantity",1))));}
    @Test void syncSnapshotSameWarehouseKitDoesNotSumScatteredComponents(){seed(10,10);WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode("VERIFY");b.setStoreCode("W2");b.setSkuSn("B");b.setBatchCode("SYNC-B");b.setZpActualNumber(10);b.setCpActualNumber(0);inventory.receive(b,"SYNC-B");db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,available_stock,stock_scope_json) VALUES ('VERIFY',1,'B',12,10,'[\"W1\",\"W2\"]')");db.update("UPDATE oms_channel_inventory SET stock_scope_json='[\"W1\",\"W2\"]'");List<Map<String,Object>> parts=Arrays.asList(kitMap("sku_sn","A","quantity",1),kitMap("sku_sn","B","quantity",1));assertEquals(0L,syncSnapshot(true,parts).get("available"));b.setStoreCode("W1");b.setBatchCode("SYNC-B2");b.setZpActualNumber(2);inventory.receive(b,"SYNC-B2");assertEquals(2L,syncSnapshot(true,parts).get("available"));}
}
