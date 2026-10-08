package com.oms.inventory;

import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.InventoryMutationService;
import com.oms.inventory.service.impl.InventoryQueryService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against the disposable MySQL schema created by docker/local/test_inventory.py. */
public class InventoryTransactionsTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db;
    static InventoryMutationService service;
    static InventoryQueryService queries;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            String url=System.getenv("INVENTORY_TEST_URL");
            if(url==null || !url.contains("/inventory_workspace_test?")) throw new IllegalStateException("Disposable test schema required");
            return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean InventoryMutationService mutations(){return new InventoryMutationService();}
        @Bean InventoryQueryService queries(){return new InventoryQueryService();}
    }
    @BeforeAll public static void start() {
        Assumptions.assumeTrue(System.getenv("INVENTORY_TEST_URL")!=null,"Run docker/local/test_inventory.py for MySQL integration tests");
        context=new AnnotationConfigApplicationContext(Config.class);
        db=context.getBean(JdbcTemplate.class);service=context.getBean(InventoryMutationService.class);queries=context.getBean(InventoryQueryService.class);
    }
    @AfterAll public static void stop(){if(context!=null)context.close();}
    @BeforeEach public void clear(){
        for(String table:Arrays.asList("wms_inventory_change_history","wms_inventory_batch","wms_inventory","oms_inventory"))db.update("DELETE FROM "+table);
    }
    private void receive(String sku,String store,String batch,int qty) {
        WmsInventoryBatch row=new WmsInventoryBatch();row.setCompanyCode("VERIFY");row.setSkuSn(sku);row.setStoreCode(store);row.setBatchCode(batch);row.setZpActualNumber(qty);row.setCpActualNumber(0);
        service.receive(row,"IN-"+store+"-"+batch);
    }
    private long scalar(String sql){return db.queryForObject(sql,Long.class);}
    private boolean reserve(String sku,String relation,int qty,boolean release,String... stores){return service.reserve("VERIFY",Arrays.asList(stores),sku,BigDecimal.valueOf(qty),relation,release);}
    private void balanced() {
        assertEquals(0,scalar("SELECT COUNT(*) FROM wms_inventory WHERE zp_actual_number<>zp_available_number+zp_lock_number OR zp_available_number<0"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM wms_inventory w WHERE zp_actual_number<>(SELECT SUM(zp_actual_number) FROM wms_inventory_batch b WHERE b.company_code=w.company_code AND b.sku_sn=w.sku_sn AND b.store_code=w.store_code) OR zp_lock_number<>(SELECT SUM(zp_lock_number) FROM wms_inventory_batch b WHERE b.company_code=w.company_code AND b.sku_sn=w.sku_sn AND b.store_code=w.store_code)"));
        assertEquals(scalar("SELECT COALESCE(SUM(zp_lock_number),0) FROM wms_inventory"),scalar("SELECT COALESCE(SUM(allocated_stock),0) FROM oms_inventory"));
    }
    @Test public void acrossWarehousesAndBatchesLocksExactlyRequestedAndReleasesOriginal() {
        receive("A","W1","B1",40);receive("A","W1","B2",60);receive("A","W2","B3",100);
        reserve("A","R1",150,false,"W2","W1");
        assertEquals(150,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));balanced();
        reserve("A","R1",150,false,"W1","W2");
        assertEquals(150,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));
        reserve("A","R1",150,true,"W2","W1");reserve("A","R1",150,true,"W1","W2");
        assertEquals(0,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));balanced();
    }
    @Test public void insufficientBatchesRollBackPartialWork() {
        receive("A","W1","B1",100);
        db.update("UPDATE oms_inventory SET total_stock=1000,available_stock=1000");
        try {reserve("A","R1",150,false,"W1");fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("不足"));}
        assertEquals(100,scalar("SELECT zp_available_number FROM wms_inventory_batch"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='LOCK'"));balanced();
    }
    @Test public void repeatedReceiveAndAdjustmentAreIdempotentAndStaleVersionsFail() {
        receive("A","W1","B1",100);receive("A","W1","B1",100);
        long id=scalar("SELECT id FROM wms_inventory_batch");int version=(int)scalar("SELECT version FROM wms_inventory_batch");
        service.adjust("VERIFY",id,version,5,"ZP","盘点增加","adjustment_request_01");
        service.adjust("VERIFY",id,version,5,"ZP","盘点增加","adjustment_request_01");
        try {service.adjust("VERIFY",id,version,5,"ZP","盘点增加","adjustment_request_02");fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("已变化"));}
        assertEquals(105,scalar("SELECT zp_actual_number FROM wms_inventory_batch"));balanced();
    }
    @Test public void tenantBoundariesAndPagedTotals() {
        for(int i=0;i<25;i++)receive("A","W1","B"+i,4);
        assertEquals(1,queries.list("VERIFY",null,null,false,false,1,20).getTotal());
        assertEquals(0,queries.list("OTHER",null,null,false,false,1,20).getTotal());
        Map<String,Object> row=queries.detail("VERIFY",scalar("SELECT id FROM wms_inventory"));
        assertEquals(25L,((Number)row.get("batchCount")).longValue());assertEquals(true,row.get("consistent"));
        assertEquals(0,queries.list("VERIFY",null,null,false,true,1,20).getTotal());
        db.update("UPDATE wms_inventory_batch SET store_code='WRONG' WHERE batch_code='B0'");
        assertEquals(1,queries.list("VERIFY",null,null,false,true,1,20).getTotal());
    }
    @Test public void emptyZeroStockIsNotAnAnomaly() {
        db.update("INSERT INTO wms_inventory(company_code,sku_sn,store_code) VALUES ('VERIFY','ZERO','W1')");
        assertEquals(0,queries.list("VERIFY",null,null,false,true,1,20).getTotal());
    }
    @Test public void boundedPageWithTenThousandInventoriesAndFiftyThousandBatches() {
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        db.update("INSERT INTO wms_inventory(company_code,sku_sn,store_code,zp_actual_number,zp_available_number) SELECT 'VERIFY',CONCAT('P',a.n,b.n,c.n,d.n),'W1',50,50 FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d");
        db.update("INSERT INTO wms_inventory_batch(company_code,sku_sn,store_code,batch_code,zp_actual_number,zp_available_number) SELECT w.company_code,w.sku_sn,w.store_code,CONCAT('B',n.n),10,10 FROM wms_inventory w CROSS JOIN "+digits+" n WHERE n.n<5");
        long start=System.nanoTime();
        for(int i=0;i<5;i++) {
            com.ruoyi.common.core.web.page.TableDataInfo result=queries.list("VERIFY",null,null,false,false,1,20);
            assertEquals(10000,result.getTotal());assertEquals(20,result.getRows().size());
            for(Object raw:result.getRows())assertEquals(true,((Map<?,?>)raw).get("consistent"));
        }
        System.out.println("PERFORMANCE 10000 inventories / 50000 batches, page 20 average ms="+(System.nanoTime()-start)/5000000.0);
        List<Map<String,Object>> plan=db.queryForList("EXPLAIN SELECT * FROM wms_inventory FORCE INDEX (idx_inventory_company_page) WHERE company_code='VERIFY' ORDER BY id DESC LIMIT 20");
        assertEquals("idx_inventory_company_page",plan.get(0).get("key"));
        assertFalse(String.valueOf(plan.get(0).get("Extra")).contains("filesort"));
    }
    @Test public void concurrentReservationsCannotOversell() throws Exception {
        receive("A","W1","B1",100);
        ExecutorService pool=Executors.newFixedThreadPool(12);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<12;i++){final String key="R"+i;tasks.add(pool.submit(()->{try{return reserve("A",key,15,false,"W1");}catch(IllegalArgumentException expected){return false;}}));}
            int success=0;for(Future<Boolean> task:tasks)if(task.get(20,TimeUnit.SECONDS))success++;
            assertEquals(6,success);assertEquals(90,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));balanced();
        } finally {pool.shutdownNow();}
    }
    @Test public void concurrentDuplicateRequestAppliesOnce() throws Exception {
        receive("A","W1","B1",100);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first=pool.submit(()->reserve("A","SAME",10,false,"W1"));
            Future<Boolean> second=pool.submit(()->reserve("A","SAME",10,false,"W1"));
            assertTrue(first.get(20,TimeUnit.SECONDS));assertTrue(second.get(20,TimeUnit.SECONDS));
            assertEquals(10,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));balanced();
        } finally {pool.shutdownNow();}
    }
    @Test public void outerFailureRollsBackHistoryAndAllBalances() {
        receive("A","W1","B1",100);
        TransactionTemplate tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        try {tx.execute(status->{reserve("A","ROLLBACK",10,false,"W1");throw new IllegalStateException("outer failure");});fail();}catch(IllegalStateException expected){ }
        assertEquals(0,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='LOCK'"));
        assertEquals(0,scalar("SELECT zp_lock_number FROM wms_inventory_batch"));balanced();
    }
    @Test public void actualDeadlockVictimRollsBackWholeTransaction() throws Exception {
        receive("A","W1","B1",100);receive("B","W1","B1",100);
        CyclicBarrier barrier=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(String first:Arrays.asList("A","B"))futures.add(pool.submit(()->{
                try {
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
                        db.queryForList("SELECT id FROM oms_inventory WHERE company_code='VERIFY' AND sku_sn=? FOR UPDATE",first);
                        try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
                        reserve(first.equals("A")?"B":"A","DEADLOCK-"+first,1,false,"W1");return null;
                    });return true;
                } catch(org.springframework.dao.DeadlockLoserDataAccessException victim){return false;}
            }));
            int successes=0;for(Future<Boolean> future:futures)if(future.get(20,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(1,scalar("SELECT SUM(zp_lock_number) FROM wms_inventory_batch"));
            assertEquals(1,scalar("SELECT COUNT(*) FROM wms_inventory_change_history WHERE operation_type='LOCK'"));balanced();
        } finally {pool.shutdownNow();}
    }
}
