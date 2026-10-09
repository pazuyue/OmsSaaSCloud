package com.oms.inventory;

import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.model.dto.ReservationCommand;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.*;
import com.oms.inventory.service.impl.rule.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.inventory.service.impl.InventoryQueryService.number;

/** Read-only product views against the same disposable MySQL as inventory transaction tests. */
public class ProductInventoryQueryTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db;
    static InventoryMutationService inventory;
    static ProductInventoryQueryService products;
    static BatchInventoryQueryService batches;
    static AllocationWorkspaceService allocations;
    static AllocationReservationService reservations;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){String url=System.getenv("INVENTORY_TEST_URL");if(url==null || !url.contains("/inventory_workspace_test?"))throw new IllegalStateException("Disposable schema required");return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean InventoryMutationService inventory(){return new InventoryMutationService();}
        @Bean InventoryQueryService warehouses(){return new InventoryQueryService();}
        @Bean ProductInventoryQueryService products(){return new ProductInventoryQueryService();}
        @Bean BatchInventoryQueryService batches(){return new BatchInventoryQueryService();}
        @Bean AllocationWorkspaceService allocations(){return new AllocationWorkspaceService();}
        @Bean AllocationReservationService reservations(){return new AllocationReservationService();}
    }
    @BeforeAll static void init(){Assumptions.assumeTrue(System.getenv("INVENTORY_TEST_URL")!=null);context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);inventory=context.getBean(InventoryMutationService.class);products=context.getBean(ProductInventoryQueryService.class);batches=context.getBean(BatchInventoryQueryService.class);allocations=context.getBean(AllocationWorkspaceService.class);reservations=context.getBean(AllocationReservationService.class);}
    @AfterAll static void stop(){if(context!=null)context.close();}
    @BeforeEach void clear(){for(String table:Arrays.asList("rule_stock_reservation_event","rule_stock_order_reservation","rule_stock_reservation","rule_stock_result","rule_stock_channel_info","rule_stock_store_code_info","rule_stock_goods_info","rule_stock_info","oms_channel_inventory","wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))db.update("DELETE FROM "+table);}
    void receive(String company,String sku,String store,String batch,int zp,int cp){WmsInventoryBatch b=new WmsInventoryBatch();b.setCompanyCode(company);b.setSkuSn(sku);b.setStoreCode(store);b.setBatchCode(batch);b.setZpActualNumber(zp);b.setCpActualNumber(cp);inventory.receive(b,"IN-"+sku+"-"+store+"-"+batch);}
    long scalar(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
    long id(){return scalar("SELECT id FROM oms_inventory WHERE company_code='VERIFY' AND sku_sn='A'");}
    Map<String,Object> row(){return products.detail("VERIFY",id());}
    Map<String,Object> map(Map<String,Object> row,String key){return (Map<String,Object>)row.get(key);}
    long lock(){AllocationDraft d=new AllocationDraft();d.setRuleName("商品视图验证");d.setAllocationType(2);d.setRuleRange(1);d.setRuleMode(2);d.setStores(Arrays.asList("W1"));AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(1);c.setChannelName("测试渠道");c.setPercentage(BigDecimal.valueOf(50));d.getChannels().add(c);long id=allocations.save("VERIFY",d,"tester");allocations.submit("VERIFY",id,1,false);allocations.start("VERIFY",id,2,"EXECUTE","tester");allocations.step("VERIFY",id,"tester");return id;}
    void order(long rule,String action,int quantity,String request){ReservationCommand c=new ReservationCommand();c.setSkuSn("A");c.setChannelId(1L);c.setOrderLine("ORDER-LINE-1");c.setQuantity(quantity);c.setRequestId(request);reservations.order("VERIFY",rule,action,c,"tester");}

    long batchId(String code){return scalar("SELECT id FROM wms_inventory_batch WHERE company_code='VERIFY' AND sku_sn='A' AND store_code='W1' AND batch_code=?",code);}
    Map<String,Object> first(com.ruoyi.common.core.web.page.TableDataInfo page){return (Map<String,Object>)page.getRows().get(0);}
    @Test void batchIdentityDoesNotMixReusedCodesOrInferMissingHistory(){
        receive("VERIFY","A","W1","SAME",100,0);receive("VERIFY","A","W2","SAME",200,0);receive("VERIFY","B","W1","SAME",300,0);receive("OTHER","A","W1","SAME",400,0);
        long id=batchId("SAME");assertEquals(1,batches.history("VERIFY",id,"",1,20).getTotal());assertEquals(100,number(first(batches.history("VERIFY",id,"RECEIVE",1,20)),"changeQuantity"));
        assertThrows(IllegalArgumentException.class,()->batches.detail("OTHER",id));assertThrows(IllegalArgumentException.class,()->batches.history("OTHER",id,"",1,20));assertThrows(IllegalArgumentException.class,()->batches.sources("OTHER",id,1,20));assertThrows(IllegalArgumentException.class,()->batches.orders("OTHER",id,1,true,1,20));assertThrows(IllegalArgumentException.class,()->batches.history("VERIFY",id,"BAD",1,20));
        db.update("UPDATE wms_inventory_change_history SET batch_id=NULL WHERE batch_id=?",id);assertEquals(0,batches.history("VERIFY",id,"",1,20).getTotal());assertEquals(100,number(batches.detail("VERIFY",id),"zpActualNumber"));
    }
    @Test void batchSourcesExplainPartialShipmentCancellationAndRelease(){
        receive("VERIFY","A","W1","B1",40,0);receive("VERIFY","A","W1","B2",60,0);long rule=lock();order(rule,"OCCUPY",45,"batch-occupy-0001");order(rule,"CONSUME",42,"batch-consume-001");order(rule,"CANCEL",1,"batch-cancel-0001");
        long a=batchId("B1"),b=batchId("B2");Map<String,Object> sa=first(batches.sources("VERIFY",a,1,20)),sb=first(batches.sources("VERIFY",b,1,20));
        assertEquals(40,number(sa,"originalQuantity"));assertEquals(40,number(sa,"consumedQuantity"));assertEquals(0,number(sa,"remainingQuantity"));assertEquals(10,number(sb,"originalQuantity"));assertEquals(8,number(sb,"remainingQuantity"));assertEquals(2,number(sb,"occupiedQuantity"));assertEquals(6,number(sb,"releasableQuantity"));assertEquals("测试渠道",sb.get("channelName"));
        Map<String,Object> summary=map(batches.detail("VERIFY",b),"reservationSummary");assertEquals(8,number(summary,"trackedLocked"));assertEquals(2,number(summary,"occupiedQuantity"));assertEquals(false,summary.get("inconsistent"));
        assertEquals(0,batches.orders("VERIFY",a,number(sa,"id"),true,1,20).getTotal());assertEquals(1,batches.orders("VERIFY",a,number(sa,"id"),false,1,20).getTotal());
        Map<String,Object> order=first(batches.orders("VERIFY",b,number(sb,"id"),true,1,20));assertEquals(5,number(order,"originalQuantity"));assertEquals(2,number(order,"consumedQuantity"));assertEquals(1,number(order,"cancelledQuantity"));assertEquals(2,number(order,"occupiedQuantity"));
        assertEquals("ORDER-LINE-1",first(batches.history("VERIFY",b,"CONSUME",1,20)).get("orderLine"));assertEquals(-2,number(first(batches.history("VERIFY",b,"CONSUME",1,20)),"changeQuantity"));
        long wrongSource=number(sb,"id");assertThrows(IllegalArgumentException.class,()->batches.orders("VERIFY",a,wrongSource,false,1,20));
        int revision=(int)scalar("SELECT revision FROM rule_stock_info WHERE id=?",rule);allocations.start("VERIFY",rule,revision,"RELEASE","tester");allocations.step("VERIFY",rule,"tester");
        sb=first(batches.sources("VERIFY",b,1,20));assertEquals(6,number(sb,"releasedQuantity"));assertEquals(2,number(sb,"remainingQuantity"));assertEquals(0,number(sb,"releasableQuantity"));assertEquals(2,number(batches.detail("VERIFY",b),"zpLockNumber"));
    }
    @Test void batchUntrackedAndInvalidSourcesRemainVisibleWithoutInventedBalances(){
        receive("VERIFY","A","W1","B1",100,0);lock();long id=batchId("B1");db.update("UPDATE rule_stock_result SET source_tracked=0");
        Map<String,Object> source=first(batches.sources("VERIFY",id,1,20)),summary=map(batches.detail("VERIFY",id),"reservationSummary");assertEquals(false,source.get("tracked"));assertNull(source.get("remainingQuantity"));assertNull(source.get("releasableQuantity"));assertEquals(50,number(summary,"otherLocked"));assertEquals(1,number(summary,"unknownSources"));
        db.update("UPDATE rule_stock_result SET source_tracked=1");db.update("UPDATE rule_stock_reservation SET occupied_quantity=60");assertEquals(true,map(batches.detail("VERIFY",id),"reservationSummary").get("inconsistent"));
    }
    @Test void batchHistoryAndOrdersPageWithinTheirOwnIndexes(){
        receive("VERIFY","A","W1","B1",100,0);lock();long id=batchId("B1");long source=number(first(batches.sources("VERIFY",id,1,20)),"id");
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        db.update("INSERT INTO rule_stock_order_reservation(company_code,rule_id,sku_sn,order_line,source_id,original_quantity,occupied_quantity,consumed_quantity) SELECT company_code,rule_id,sku_sn,CONCAT('O-',a.n,b.n,c.n),id,1,IF(a.n=0,1,0),IF(a.n=0,0,1) FROM rule_stock_reservation CROSS JOIN "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c");
        long begin=System.nanoTime();assertEquals(1000,batches.orders("VERIFY",id,source,false,1,20).getTotal());assertEquals(20,batches.orders("VERIFY",id,source,false,2,20).getRows().size());assertEquals(100,batches.orders("VERIFY",id,source,true,1,1000).getRows().size());System.out.println("BATCH orders 1000 rows paged queries ms="+(System.nanoTime()-begin)/1000000.0);
        Map<?,?> plan=db.queryForMap("EXPLAIN SELECT * FROM rule_stock_order_reservation WHERE company_code='VERIFY' AND source_id=? ORDER BY id DESC LIMIT 20",source);assertEquals("idx_order_source_page",plan.get("key"));assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"));
        assertEquals(2,batches.history("VERIFY",id,"",1,1).getTotal());assertNotEquals(first(batches.history("VERIFY",id,"",1,1)).get("logId"),first(batches.history("VERIFY",id,"",2,1)).get("logId"));
    }
    @Test void batchReadsDoNotWaitForLockedStockRows() throws Exception {
        receive("VERIFY","A","W1","B1",100,0);long id=batchId("B1");CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {Future<?> writer=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(tx->{db.update("UPDATE wms_inventory_batch SET zp_actual_number=999 WHERE id=?",id);locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}tx.setRollbackOnly();return null;}));assertTrue(locked.await(5,TimeUnit.SECONDS));Future<Map<String,Object>> reader=pool.submit(()->batches.detail("VERIFY",id));assertEquals(100,number(reader.get(3,TimeUnit.SECONDS),"zpActualNumber"));release.countDown();writer.get(5,TimeUnit.SECONDS);}finally{release.countDown();pool.shutdownNow();}
    }

    @Test void separatesQualityAndDoesNotMultiplyWarehouseStockByBatchCount(){
        receive("VERIFY","A","W1","B1",40,5);receive("VERIFY","A","W1","B2",60,5);receive("VERIFY","A","W2","B3",200,0);
        Map<String,Object> row=row(),warehouse=map(row,"warehouseTotals");assertEquals(310,number(row,"totalStock"));assertEquals(300,number(warehouse,"zpAvailableNumber"));assertEquals(10,number(warehouse,"cpAvailableNumber"));assertEquals(2,number(warehouse,"warehouseCount"));assertEquals(3,number(map(row,"batchTotals"),"batchCount"));assertEquals("CONSISTENT",row.get("checkStatus"));
        assertEquals(2,products.warehouses("VERIFY",id(),1,1).getTotal());assertEquals(1,products.warehouses("VERIFY",id(),1,1).getRows().size());
    }
    @Test void missingBatchAndSummaryMismatchAreVisibleWithoutRepairingData(){
        receive("VERIFY","A","W1","B1",300,0);db.update("INSERT INTO wms_inventory(company_code,sku_sn,store_code,zp_actual_number,zp_available_number) VALUES ('VERIFY','A','W2',100,100)");
        Map<String,Object> row=row();assertEquals("INCOMPLETE",row.get("checkStatus"));assertEquals(300,number(row,"totalStock"));assertEquals(400,number(map(row,"warehouseTotals"),"totalStock"));assertEquals(300,number(map(row,"batchTotals"),"totalStock"));assertEquals(300,scalar("SELECT total_stock FROM oms_inventory"));
        db.update("DELETE FROM wms_inventory WHERE store_code='W2'");db.update("UPDATE oms_inventory SET total_stock=310,available_stock=310");assertEquals("DIFFERENT",row().get("checkStatus"));
    }
    @Test void orphanAndOffsettingBatchErrorsCannotLookConsistent(){
        receive("VERIFY","A","W1","B1",40,0);receive("VERIFY","A","W1","B2",60,0);
        db.update("UPDATE wms_inventory_batch SET zp_available_number=zp_available_number+IF(batch_code='B1',1,-1)");assertEquals("DIFFERENT",row().get("checkStatus"));
        db.update("UPDATE wms_inventory_batch SET zp_available_number=zp_actual_number");db.update("INSERT INTO wms_inventory_batch(company_code,sku_sn,store_code,batch_code,zp_actual_number,zp_available_number) VALUES ('VERIFY','A','ORPHAN','B3',1,1)");assertEquals("INCOMPLETE",row().get("checkStatus"));
    }
    @Test void tenantBoundariesApplyToEveryDrilldownAndSkuFiltersAreLiteral(){
        receive("VERIFY","A","W1","B1",100,0);receive("OTHER","A","W1","B1",900,0);
        assertEquals(100,number(row(),"totalStock"));assertEquals(100,number(map(row(),"warehouseTotals"),"totalStock"));
        assertEquals(0,products.list("VERIFY","A' OR 1=1",false,1,20).getTotal());assertEquals(0,products.list("EMPTY",null,false,1,20).getTotal());
        assertThrows(IllegalArgumentException.class,()->products.detail("OTHER",id()));assertThrows(IllegalArgumentException.class,()->products.warehouses("OTHER",id(),1,20));assertThrows(IllegalArgumentException.class,()->products.reservations("OTHER",id(),1,20));assertThrows(IllegalArgumentException.class,()->products.history("OTHER",id(),"",1,20));assertThrows(IllegalArgumentException.class,()->products.history("VERIFY",id(),"UNKNOWN",1,20));
    }
    @Test void sourceBalancesAndShipmentHistoryExplainOrderOccupation(){
        receive("VERIFY","A","W1","B1",100,0);long rule=lock();order(rule,"OCCUPY",30,"occupy-product-test");order(rule,"CONSUME",10,"consume-product-test");
        Map<String,Object> row=row(),summary=map(row,"reservationSummary");assertEquals(90,number(row,"totalStock"));assertEquals(40,number(summary,"trackedLocked"));assertEquals(20,number(summary,"occupiedQuantity"));assertEquals(0,number(summary,"otherLocked"));assertEquals("CONSISTENT",row.get("checkStatus"));
        Map<String,Object> source=(Map<String,Object>)products.reservations("VERIFY",id(),1,20).getRows().get(0);assertEquals(20,number(source,"releasableQuantity"));assertEquals(1,((List<?>)source.get("channels")).size());
        Map<String,Object> history=(Map<String,Object>)products.history("VERIFY",id(),"CONSUME",1,20).getRows().get(0);assertEquals("ORDER-LINE-1",history.get("orderLine"));assertEquals("RULE-"+rule,history.get("relationSn"));
        db.update("UPDATE rule_stock_result SET source_tracked=0 WHERE rule_id=?",rule);source=(Map<String,Object>)products.reservations("VERIFY",id(),1,20).getRows().get(0);assertNull(source.get("releasableQuantity"));
    }
    @Test void readOnlyViewsDoNotWaitForStockWriteLocks() throws Exception {
        receive("VERIFY","A","W1","B1",100,0);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> writer=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(tx->{db.queryForList("SELECT id FROM oms_inventory WHERE company_code='VERIFY' AND sku_sn='A' FOR UPDATE");db.update("UPDATE oms_inventory SET total_stock=999 WHERE company_code='VERIFY' AND sku_sn='A'");locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}tx.setRollbackOnly();return null;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));Future<Map<String,Object>> reader=pool.submit(()->row());assertEquals(100,number(reader.get(3,TimeUnit.SECONDS),"totalStock"));release.countDown();writer.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void aggregatesOnlyTheCurrentPageAtTenThousandSkus(){
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        db.update("INSERT INTO oms_inventory(company_code,sku_sn,total_stock,available_stock) SELECT 'VERIFY',CONCAT('P',a.n,b.n,c.n,d.n),100,100 FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d");
        db.update("INSERT INTO wms_inventory(company_code,sku_sn,store_code,zp_actual_number,zp_available_number) SELECT company_code,sku_sn,'W1',100,100 FROM oms_inventory");
        db.update("INSERT INTO wms_inventory_batch(company_code,sku_sn,store_code,batch_code,zp_actual_number,zp_available_number) SELECT company_code,sku_sn,'W1','B1',40,40 FROM oms_inventory");db.update("INSERT INTO wms_inventory_batch(company_code,sku_sn,store_code,batch_code,zp_actual_number,zp_available_number) SELECT company_code,sku_sn,'W1','B2',60,60 FROM oms_inventory");
        long begin=System.nanoTime();com.ruoyi.common.core.web.page.TableDataInfo page=products.list("VERIFY",null,true,1,20);System.out.println("PRODUCT INVENTORY 10000 SKUs / 20000 batches page20 ms="+(System.nanoTime()-begin)/1000000.0);
        assertEquals(10000,page.getTotal());assertEquals(20,page.getRows().size());for(Object item:page.getRows())assertEquals("CONSISTENT",((Map<?,?>)item).get("checkStatus"));assertEquals(100,products.list("VERIFY",null,false,1,1000).getRows().size());
        Map<?,?> plan=db.queryForMap("EXPLAIN SELECT * FROM oms_inventory FORCE INDEX (idx_oms_company_page) WHERE company_code='VERIFY' ORDER BY id DESC LIMIT 20");assertEquals("idx_oms_company_page",plan.get("key"));assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"));
    }
}
