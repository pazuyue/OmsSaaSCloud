package com.oms.order.service;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderRepositoryTest {
    static AnnotationConfigApplicationContext context;static JdbcTemplate db;static OrderRepository repository;
    static final String COMPANY="VERIFY",LEASE="0123456789abcdef0123456789abcdef";
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){String url=System.getenv("ORDER_TEST_URL");if(url==null||!url.contains("/order_workspace_test?"))throw new IllegalStateException("Disposable schema required");com.alibaba.druid.pool.DruidDataSource pool=new com.alibaba.druid.pool.DruidDataSource();pool.setUrl(url);pool.setUsername("root");pool.setPassword(System.getenv("INVENTORY_TEST_PASSWORD"));pool.setInitialSize(1);pool.setMinIdle(1);pool.setMaxActive(8);pool.setMaxWait(10000);return pool;}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean OrderRepository repository(){return new OrderRepository();}
    }
    @BeforeAll static void start(){Assumptions.assumeTrue(System.getenv("ORDER_TEST_URL")!=null);context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);repository=context.getBean(OrderRepository.class);}
    @AfterAll static void stop(){if(context!=null)context.close();}
    @BeforeEach void clear(){for(String t:Arrays.asList("oms_order_review_event","oms_order_review_config","oms_order_process_log","oms_order_line","oms_order","oms_platform_order_line","oms_platform_order","oms_order_pull_task","oms_order_pull_config"))db.update("DELETE FROM "+t);db.update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,source_key,seller_nick,owner_code,store_codes,initial_time,cursor_time) VALUES ('VERIFY',1,'test','fixture','seller','OWNER','[\"W1\"]','2026-10-01 00:00:00','2026-10-01 00:00:00')");Map<String,Object> reviewPolicy=OrderAutoReviewPolicy.defaults();reviewPolicy.put("mode","AUTO");db.update("INSERT INTO oms_order_review_config(company_code,channel_id,policy_json) VALUES ('VERIFY',1,?)",OrderValues.encode(reviewPolicy));assertTrue(repository.claim(COMPANY,1,LEASE));}
    Map<String,Object> row(String modified,String payload){Map<String,Object> r=new HashMap<>();r.put("tid","987654321012345678");r.put("status","WAIT_SELLER_SEND_GOODS");r.put("type","fixed");r.put("seller_nick","seller");r.put("created","2026-10-01 00:00:00");r.put("modified",modified);r.put("jdp_modified",modified);r.put("jdp_response",payload);return r;}
    Map<String,Object> task(String type){repository.newTask(COMPANY,1,type,"2026-10-01 00:00:00","2026-10-02 00:00:00","test");return repository.nextTask(COMPANY,1,LEASE);}
    long n(String sql){return db.queryForObject(sql,Long.class);}
    @Test void bindingAndSkuSnapshotCannotBeRewrittenAfterDemandPrepared(){
        Map<String,Object> task=task("MANUAL");String payload=TmallOrderParserTest.payload("25");repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 01:00:00",payload)),50);long id=n("SELECT id FROM oms_platform_order");repository.snapshot(COMPANY,id,LEASE);TmallOrderParser.Trade trade=new TmallOrderParser().parse(payload);String oid=trade.lines.get(0).oid;repository.parsed(COMPANY,id,LEASE,trade,Collections.singletonMap(oid,"A"),Collections.emptyMap());Map<String,Object> b=new HashMap<>();b.put("binding_id",22);b.put("binding_revision",3);b.put("source","PLATFORM_BINDING");repository.bindingSnapshots(COMPANY,id,LEASE,Collections.singletonMap(oid,b));repository.prepareDemand(COMPANY,id,LEASE);repository.parsed(COMPANY,id,LEASE,trade,Collections.singletonMap(oid,"B"),Collections.emptyMap());b.put("binding_revision",4);repository.bindingSnapshots(COMPANY,id,LEASE,Collections.singletonMap(oid,b));assertEquals("A",repository.frozenMatches(COMPANY,id).get(oid));assertEquals(3,n("SELECT product_binding_revision FROM oms_platform_order_line"));assertEquals(22,n("SELECT product_binding_id FROM oms_platform_order_line"));
    }
    @Test void manualSelectionProtectsSnapshotBeforeInventoryRegistration(){
        Map<String,Object> task=task("MANUAL");String payload=TmallOrderParserTest.payload("25");repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 01:00:00",payload)),50);
        long id=n("SELECT id FROM oms_platform_order");repository.snapshot(COMPANY,id,LEASE);TmallOrderParser.Trade trade=new TmallOrderParser().parse(payload);repository.parsed(COMPANY,id,LEASE,trade,Collections.emptyMap(),Collections.singletonMap(trade.lines.get(0).oid,"missing"));db.update("UPDATE oms_platform_order_line SET manual_sku_sn='NEW'");
        repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 02:00:00",TmallOrderParserTest.payload("30"))),50);
        assertEquals(1,n("SELECT change_pending FROM oms_platform_order"));assertEquals(payload,repository.raw(COMPANY,id).get("processing_payload"));assertEquals(0,n("SELECT demand_registered FROM oms_platform_order"));assertThrows(IllegalArgumentException.class,()->repository.snapshot(COMPANY,id,LEASE));
    }
    long exceptionOrder(){return repository.insert("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('VERIFY',1,'123456','WAIT_SELLER_SEND_GOODS','fixed','seller',NOW(),'test','{}','EXCEPTION')");}
    OrderBusinessService businessService(){OrderBusinessService service=new OrderBusinessService();org.springframework.test.util.ReflectionTestUtils.setField(service,"repository",repository);return service;}
    @Test void concurrentBusinessRetryQueuesOnlyOnce() throws Exception {
        long id=businessOrder();repository.release(COMPANY,1,LEASE);OrderBusinessService service=businessService();
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try{List<java.util.concurrent.Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return Boolean.TRUE.equals(service.retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));}));start.countDown();int submitted=0;for(java.util.concurrent.Future<Boolean> r:results)if(r.get(10,java.util.concurrent.TimeUnit.SECONDS))submitted++;assertEquals(1,submitted);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action='STOCK_RETRY_REQUEST'"));}finally{pool.shutdownNow();}
    }
    long businessOrder(){long platform=exceptionOrder();long order=repository.insert("INSERT INTO oms_order(company_code,order_sn,channel_id,platform_order_id,platform_tid,inventory_status,payment) VALUES ('VERIFY','SOTEST',1,?,'123456','WAIT_RESERVE',120)",platform);db.update("UPDATE oms_platform_order SET business_order_id=?,conversion_status='SUCCESS' WHERE id=?",order,platform);db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,reserved_quantity,inventory_reason,inventory_reason_code) VALUES ('VERIFY',?,'1234561','A','platform title',120,120,0,'等待同SKU足量预占','SHORTAGE')",order);return order;}
    @Test void businessFiltersAreBeforePaginationAndListHasNoRecipientData(){
        long id=businessOrder();OrderBusinessService service=businessService();
        assertEquals(1,service.list(COMPANY,null,"SOTEST","","A","","",0,1,20).getTotal());
        assertEquals(0,service.list(COMPANY,null,"","","B","","",0,1,20).getTotal());
        assertEquals(0,service.list("OTHER",null,"","","","","",0,1,20).getTotal());
        assertEquals(0,service.list(COMPANY,null,"","","","","",60,1,20).getTotal());
        Map<String,Object> row=(Map<String,Object>)service.list(COMPANY,null,"","","","","",0,1,20).getRows().get(0);
        assertEquals(120,OrderValues.number(row,"pending_quantity"));assertFalse(row.containsKey("receiver_address"));assertFalse(row.containsKey("raw_payload"));
        db.update("UPDATE oms_platform_order SET change_pending=1");assertEquals(1,service.list(COMPANY,null,"","SOURCE_CHANGED","","","",0,1,20).getTotal());
        Map<String,Object> changed=service.results(COMPANY,Arrays.asList(id)).get(0);assertEquals(false,changed.get("can_reserve"));assertNull(changed.get("next_retry_at"));
    }
    @Test void businessRetryIsIdempotentScopedAndDoesNotReconvert(){
        long id=businessOrder();OrderBusinessService service=businessService();
        assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"tester").get(0).get("submitted"));repository.release(COMPANY,1,LEASE);
        assertEquals(false,service.retry("OTHER",Arrays.asList(id),"tester").get(0).get("submitted"));
        db.update("UPDATE oms_platform_order SET change_pending=1");assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"tester").get(0).get("submitted"));db.update("UPDATE oms_platform_order SET change_pending=0");
        assertEquals(true,service.retry(COMPANY,Arrays.asList(id),"tester").get(0).get("submitted"));assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"tester").get(0).get("submitted"));
        assertEquals("SUCCESS",db.queryForObject("SELECT conversion_status FROM oms_platform_order",String.class));assertEquals("QUEUED",service.results(COMPANY,Arrays.asList(id)).get(0).get("stock_attempt_status"));
        assertEquals(1,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action='STOCK_RETRY_REQUEST'"));
        repository.claim(COMPANY,1,LEASE);long platform=n("SELECT id FROM oms_platform_order");repository.snapshot(COMPANY,platform,LEASE);repository.failure(COMPANY,platform,LEASE,"SYSTEM_FAILURE","service unavailable",Collections.emptyList(),true);repository.failure(COMPANY,platform,LEASE,"SYSTEM_FAILURE","service unavailable",Collections.emptyList(),true);
        assertEquals("FAILED",service.results(COMPANY,Arrays.asList(id)).get(0).get("stock_attempt_status"));assertEquals(1,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action='STOCK_RETRY'"));
        db.update("UPDATE oms_order SET inventory_status='RESERVED'");repository.release(COMPANY,1,LEASE);assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"tester").get(0).get("submitted"));
    }
    @Test void systemFailuresBackOffAndStopAfterFiveRetries(){
        long id=exceptionOrder();int[] delays={60,120,300,600,1800,1800};
        for(int i=0;i<6;i++){
            repository.failure(COMPANY,id,LEASE,"SYSTEM_FAILURE","服务失败",Collections.emptyList(),true);
            Map<String,Object> row=repository.raw(COMPANY,id);
            assertEquals(i<5?"PENDING":"EXCEPTION",row.get("conversion_status"));assertEquals(i+1,OrderValues.number(row,"system_failures"));
            long seconds=n("SELECT TIMESTAMPDIFF(SECOND,NOW(),next_retry_at) FROM oms_platform_order");assertTrue(seconds>=delays[i]-3&&seconds<=delays[i]);
        }
        db.update("UPDATE oms_platform_order SET next_retry_at='2000-01-01'");assertTrue(repository.dueOrders(COMPANY,1,true).isEmpty());
        assertNotNull(repository.raw(COMPANY,id).get("first_exception_at"));
    }
    @Test void batchRetryRechecksLeaseCompanyStateAndChangedSource(){
        long id=exceptionOrder();OrderExceptionService service=new OrderExceptionService(repository);
        assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));
        repository.release(COMPANY,1,LEASE);
        assertEquals(false,service.retry("OTHER",Arrays.asList(id),"test").get(0).get("submitted"));
        db.update("UPDATE oms_platform_order SET change_pending=1");assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));
        db.update("UPDATE oms_platform_order SET change_pending=0");assertEquals(true,service.retry(COMPANY,Arrays.asList(id,id),"test").get(0).get("submitted"));
        assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));assertEquals(1,n("SELECT retry_count FROM oms_platform_order"));
        assertTrue(service.results("OTHER",Arrays.asList(id)).isEmpty());assertThrows(IllegalArgumentException.class,()->service.retry(COMPANY,Collections.nCopies(101,id),"test"));
        db.update("UPDATE oms_platform_order SET conversion_status='SUCCESS'");assertEquals(false,service.retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));
    }
    @Test void concurrentRetriesHaveOnlyOneSubmission() throws Exception {
        long id=exceptionOrder();repository.release(COMPANY,1,LEASE);OrderExceptionService service=new OrderExceptionService(repository);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try{List<java.util.concurrent.Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return Boolean.TRUE.equals(service.retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));}));start.countDown();int submitted=0;for(java.util.concurrent.Future<Boolean> result:results)if(result.get(10,java.util.concurrent.TimeUnit.SECONDS))submitted++;assertEquals(1,submitted);assertEquals(1,n("SELECT retry_count FROM oms_platform_order"));}finally{pool.shutdownNow();}
    }
    @Test void fullLineErrorsAndFiltersUseStructuredData(){
        long id=exceptionOrder();List<Map<String,Object>> details=new ArrayList<>();for(int i=0;i<20;i++){Map<String,Object> d=new HashMap<>();d.put("platform_oid",String.valueOf(i));d.put("message","缺少商品资料"+i);details.add(d);}
        repository.failure(COMPANY,id,LEASE,"PRODUCT_INVALID","缺少商品资料",details,false);
        OrderWorkspaceService workspace=new OrderWorkspaceService();org.springframework.test.util.ReflectionTestUtils.setField(workspace,"repository",repository);
        assertEquals(20,((List<?>)workspace.detail(COMPANY,id,false).get("exception_details")).size());
        assertEquals(1,workspace.list(COMPANY,"exceptions",null,"","",1,20,"PRODUCT","","","",0).getTotal());
        assertEquals(0,workspace.list(COMPANY,"exceptions",null,"","",1,20,"SYSTEM","","","",0).getTotal());
        assertEquals(0,workspace.list(COMPANY,"exceptions",null,"","",1,20,"PRODUCT","","","",60).getTotal());
        repository.release(COMPANY,1,LEASE);workspace.retry(COMPANY,id,"test");
        assertEquals(1,workspace.list(COMPANY,"exceptions",null,"","",1,20).getTotal());
        repository.claim(COMPANY,1,LEASE);repository.state(COMPANY,id,LEASE,"WAITING","待付款");assertEquals(0,workspace.list(COMPANY,"exceptions",null,"","",1,20).getTotal());
    }
    @Test void waitingOrdersFollowChangedTypeConfiguration(){
        db.update("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,source_modified,payload_hash,raw_payload,conversion_status,next_retry_at) VALUES ('VERIFY',1,'9000465448054981208','WAIT_SELLER_SEND_GOODS','tmall_i18n','seller',NOW(),'test','{}','WAITING','2000-01-01 00:00:00')");
        db.update("UPDATE oms_order_pull_config SET allowed_trade_types='[\"fixed\"]'");assertTrue(repository.dueOrders(COMPANY,1,true).isEmpty());
        db.update("UPDATE oms_order_pull_config SET allowed_trade_types='[\"tmall_i18n\"]'");assertEquals(1,repository.dueOrders(COMPANY,1,true).size());
        assertTrue(repository.dueOrders(COMPANY,1,false).isEmpty());
        db.update("UPDATE oms_platform_order SET platform_status='WAIT_BUYER_PAY'");assertTrue(repository.dueOrders(COMPANY,1,true).isEmpty());
    }
    @Test void retriedFirstBatchDoesNotStarveLaterOrders(){
        List<Long> ids=new ArrayList<>();for(int i=0;i<12;i++)ids.add(repository.insert("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,source_modified,payload_hash,raw_payload,next_retry_at) VALUES ('VERIFY',1,?,'WAIT_SELLER_SEND_GOODS','fixed','seller',NOW(),'test','{}','2000-01-01 00:00:00')",String.valueOf(2000+i)));
        List<Map<String,Object>> first=repository.dueOrders(COMPANY,1,true);assertEquals(10,first.size());
        for(Map<String,Object> row:first)repository.state(COMPANY,OrderValues.number(row,"id"),LEASE,"PENDING","待重试");
        // Even after those retries become due again, the unattempted tail goes first.
        db.update("UPDATE oms_platform_order SET next_retry_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id<=?",ids.get(9));
        List<Map<String,Object>> next=repository.dueOrders(COMPANY,1,true);
        assertEquals(ids.get(10).longValue(),OrderValues.number(next.get(0),"id"));assertEquals(ids.get(11).longValue(),OrderValues.number(next.get(1),"id"));
    }
    @Test void backfillIsIdempotentAndDoesNotAdvanceAutoCursor(){Map<String,Object> task=task("MANUAL");Map<String,Object> row=row("2026-10-01 01:00:00",TmallOrderParserTest.payload("25"));repository.page(COMPANY,1,LEASE,task,Arrays.asList(row),50);repository.page(COMPANY,1,LEASE,task,Arrays.asList(row),50);assertEquals(1,n("SELECT COUNT(*) FROM oms_platform_order"));assertEquals("2026-10-01 00:00:00",OrderValues.text(repository.config(COMPANY,1),"cursor_time"));assertThrows(IllegalArgumentException.class,()->repository.raw("OTHER",n("SELECT id FROM oms_platform_order")));}
    @Test void autoCursorCommitsWithPageAndExpiredLeaseCannotCommit(){Map<String,Object> task=task("AUTO");repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 01:00:00",TmallOrderParserTest.payload("25"))),50);assertEquals("2026-10-02 00:00:00",OrderValues.text(repository.config(COMPANY,1),"cursor_time"));repository.release(COMPANY,1,LEASE);assertThrows(IllegalArgumentException.class,()->repository.page(COMPANY,1,LEASE,task,Collections.emptyList(),50));}
    @Test void registeredSnapshotCannotBeRewrittenByNewerPlatformPayload(){Map<String,Object> task=task("MANUAL");repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 01:00:00",TmallOrderParserTest.payload("25"))),50);long id=n("SELECT id FROM oms_platform_order");repository.snapshot(COMPANY,id,LEASE);repository.registered(COMPANY,id,LEASE);repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 02:00:00",TmallOrderParserTest.payload("30"))),50);assertEquals(1,n("SELECT change_pending FROM oms_platform_order"));assertTrue(OrderValues.text(repository.raw(COMPANY,id),"processing_payload").contains("\"25\""));assertThrows(IllegalArgumentException.class,()->repository.snapshot(COMPANY,id,LEASE));}
    @Test void conversionCommitIsIdempotentAndShortageCanLaterReserve(){Map<String,Object> task=task("MANUAL");String payload=TmallOrderParserTest.payload("25");repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 01:00:00",payload)),50);long id=n("SELECT id FROM oms_platform_order");repository.snapshot(COMPANY,id,LEASE);TmallOrderParser.Trade trade=new TmallOrderParser().parse(payload);repository.parsed(COMPANY,id,LEASE,trade,Collections.singletonMap(trade.lines.get(0).oid,"A"),Collections.emptyMap());Map<String,Object> stock=new HashMap<>();stock.put("platform_oid",trade.lines.get(0).oid);stock.put("sku_sn","A");stock.put("reserved_quantity",0);stock.put("reason","shortage");Map<String,Object> config=repository.config(COMPANY,1);repository.complete(COMPANY,id,LEASE,config,Arrays.asList(stock));assertEquals("WAIT_RESERVE",db.queryForObject("SELECT inventory_status FROM oms_order",String.class));assertEquals("APPROVED",db.queryForObject("SELECT review_status FROM oms_order",String.class));stock.put("reserved_quantity",5);stock.put("reason","");repository.complete(COMPANY,id,LEASE,config,Arrays.asList(stock));repository.complete(COMPANY,id,LEASE,config,Arrays.asList(stock));assertEquals(1,n("SELECT COUNT(*) FROM oms_order"));assertEquals(1,n("SELECT COUNT(*) FROM oms_order_line"));assertEquals("RESERVED",db.queryForObject("SELECT inventory_status FROM oms_order",String.class));}

    Map<String,Object> review(String company,String action,long id,long version){OrderReviewService service=new OrderReviewService();org.springframework.test.util.ReflectionTestUtils.setField(service,"repository",repository);Map<String,Object> input=new HashMap<>(),order=new HashMap<>();order.put("id",id);order.put("control_version",version);input.put("orders",Arrays.asList(order));input.put("reason","test reason");return service.apply(company,action,input,"tester").get(0);}
    @Test void reviewHoldAndResumeAreIndependentAndPreserveStock(){
        long id=businessOrder();repository.release(COMPANY,1,LEASE);
        assertEquals(true,review(COMPANY,"hold",id,0).get("success"));
        assertEquals(false,businessService().retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));
        assertEquals(true,review(COMPANY,"approve",id,1).get("success"));assertEquals(1,n("SELECT manual_hold FROM oms_order"));
        assertEquals("APPROVED",db.queryForObject("SELECT review_status FROM oms_order",String.class));
        assertEquals(false,review(COMPANY,"resume",id,1).get("success"));
        assertEquals(true,review(COMPANY,"resume",id,2).get("success"));assertEquals(0,n("SELECT manual_hold FROM oms_order"));
        assertEquals(0,n("SELECT reserved_quantity FROM oms_order_line"));assertEquals(120,n("SELECT quantity FROM oms_order_line"));
        assertEquals(false,businessService().results(COMPANY,Arrays.asList(id)).get(0).get("release_eligible"));
        db.update("UPDATE oms_order SET inventory_status='RESERVED'");assertEquals(true,businessService().results(COMPANY,Arrays.asList(id)).get(0).get("release_eligible"));
        assertEquals(3,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action IN ('REVIEW_APPROVE','ORDER_HOLD','ORDER_RESUME')"));
    }
    @Test void heldOrdersAreExcludedFromQueueAndSnapshot(){
        long id=businessOrder();repository.release(COMPANY,1,LEASE);assertEquals(true,review(COMPANY,"hold",id,0).get("success"));
        assertTrue(repository.dueOrders(COMPANY,1,true).isEmpty());repository.claim(COMPANY,1,LEASE);
        assertNull(repository.snapshot(COMPANY,n("SELECT id FROM oms_platform_order"),LEASE));assertEquals("DONE",db.queryForObject("SELECT stock_attempt_status FROM oms_order",String.class));
    }
    @Test void platformBlockCannotBeOverriddenByReviewOrResume(){
        long id=businessOrder();assertEquals(false,review(COMPANY,"approve",id,0).get("success"));repository.release(COMPANY,1,LEASE);
        assertEquals(false,review("OTHER","approve",id,0).get("success"));
        assertEquals(true,review(COMPANY,"hold",id,0).get("success"));db.update("UPDATE oms_platform_order SET change_pending=1");
        assertEquals(false,review(COMPANY,"approve",id,1).get("success"));assertEquals(false,review(COMPANY,"resume",id,1).get("success"));assertEquals(1,n("SELECT manual_hold FROM oms_order"));
    }
    @Test void concurrentReviewIsVersionedAndLoggedOnce() throws Exception {
        long id=businessOrder();repository.release(COMPANY,1,LEASE);java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{List<java.util.concurrent.Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(pool.submit(()->review(COMPANY,"approve",id,0)));int success=0;for(java.util.concurrent.Future<Map<String,Object>> f:futures)if(Boolean.TRUE.equals(f.get(10,java.util.concurrent.TimeUnit.SECONDS).get("success")))success++;assertEquals(1,success);assertEquals(1,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action='REVIEW_APPROVE'"));}finally{pool.shutdownNow();}
    }
    @Test void manualMatchRequiresReviewAndLaterStockDoesNotReapprove(){
        Map<String,Object> task=task("MANUAL");String payload=TmallOrderParserTest.payload("25");repository.page(COMPANY,1,LEASE,task,Arrays.asList(row("2026-10-01 01:00:00",payload)),50);long id=n("SELECT id FROM oms_platform_order");repository.snapshot(COMPANY,id,LEASE);TmallOrderParser.Trade trade=new TmallOrderParser().parse(payload);repository.parsed(COMPANY,id,LEASE,trade,Collections.singletonMap(trade.lines.get(0).oid,"A"),Collections.emptyMap());db.update("UPDATE oms_platform_order_line SET manual_sku_sn='A'");Map<String,Object> stock=new HashMap<>();stock.put("platform_oid",trade.lines.get(0).oid);stock.put("sku_sn","A");stock.put("reserved_quantity",0);stock.put("reason","shortage");repository.complete(COMPANY,id,LEASE,repository.config(COMPANY,1),Arrays.asList(stock));assertEquals("PENDING",db.queryForObject("SELECT review_status FROM oms_order",String.class));assertEquals("MANUAL_MATCH",db.queryForObject("SELECT review_policy FROM oms_order",String.class));db.update("UPDATE oms_order_review_config SET policy_json=JSON_SET(policy_json,'$.manual_match_required',0),revision=revision+1");stock.put("reserved_quantity",5);repository.complete(COMPANY,id,LEASE,repository.config(COMPANY,1),Arrays.asList(stock));assertEquals("PENDING",db.queryForObject("SELECT review_status FROM oms_order",String.class));
    }

    OrderVoidService voidService(com.oms.common.api.OrderStockClient inventory){OrderVoidService service=new OrderVoidService();org.springframework.test.util.ReflectionTestUtils.setField(service,"repository",repository);org.springframework.test.util.ReflectionTestUtils.setField(service,"inventory",inventory);return service;}
    Map<String,Object> voidBody(long id,long version){Map<String,Object> body=new HashMap<>(),order=new HashMap<>();order.put("id",id);order.put("control_version",version);body.put("orders",Arrays.asList(order));body.put("reason","test cancellation");body.put("confirm_local_void",1);return body;}
    List<Map<String,Object>> cancelledStock(){Map<String,Object> line=new HashMap<>();line.put("platform_oid","1234561");line.put("sku_sn","A");line.put("demand_quantity",120);line.put("reserved_quantity",0);line.put("status","CANCELLED");return Arrays.asList(line);}
    @Test void voidIntentStopsEveryOtherActionAndCompletionIsIdempotent(){
        long id=businessOrder();com.oms.common.api.OrderStockClient client=org.mockito.Mockito.mock(com.oms.common.api.OrderStockClient.class);OrderVoidService service=voidService(client);
        assertEquals(false,service.request(COMPANY,voidBody(id,0),"tester").get(0).get("submitted"));repository.release(COMPANY,1,LEASE);
        assertEquals(false,service.request("OTHER",voidBody(id,0),"tester").get(0).get("submitted"));
        db.update("UPDATE oms_order SET manual_hold=1");db.update("UPDATE oms_platform_order SET change_pending=1");
        assertEquals(true,service.request(COMPANY,voidBody(id,0),"tester").get(0).get("submitted"));assertEquals(1,service.due(COMPANY,1).size());
        assertTrue(repository.dueOrders(COMPANY,1,true).isEmpty());assertEquals(false,review(COMPANY,"resume",id,1).get("success"));assertEquals(false,businessService().retry(COMPANY,Arrays.asList(id),"test").get(0).get("submitted"));
        org.mockito.Mockito.when(client.execute(org.mockito.ArgumentMatchers.eq("cancel"),org.mockito.ArgumentMatchers.eq(COMPANY),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(com.ruoyi.common.core.domain.R.ok(cancelledStock()));
        repository.claim(COMPANY,1,LEASE);service.process(COMPANY,id,1,LEASE);service.process(COMPANY,id,1,LEASE);
        assertEquals("VOIDED",db.queryForObject("SELECT status FROM oms_order",String.class));assertEquals("RELEASED",db.queryForObject("SELECT inventory_status FROM oms_order",String.class));assertEquals(1,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action='VOID_COMPLETE'"));assertEquals(120,n("SELECT quantity FROM oms_order_line"));assertEquals(0,OrderValues.number(businessService().results(COMPANY,Arrays.asList(id)).get(0),"pending_quantity"));
        assertTrue(service.due(COMPANY,1).isEmpty());repository.release(COMPANY,1,LEASE);assertEquals("VOIDED",service.request(COMPANY,voidBody(id,0),"tester").get(0).get("status"));
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).execute(org.mockito.ArgumentMatchers.eq("cancel"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }
    @Test void releaseTimeoutKeepsVoidIntentAndRetryPreservesOriginalReason(){
        long id=businessOrder();repository.release(COMPANY,1,LEASE);com.oms.common.api.OrderStockClient client=org.mockito.Mockito.mock(com.oms.common.api.OrderStockClient.class);OrderVoidService service=voidService(client);service.request(COMPANY,voidBody(id,0),"first");
        org.mockito.Mockito.when(client.execute(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("timeout after remote commit")).thenReturn(com.ruoyi.common.core.domain.R.ok(cancelledStock()));
        repository.claim(COMPANY,1,LEASE);service.process(COMPANY,id,1,LEASE);assertEquals("VOIDING",db.queryForObject("SELECT status FROM oms_order",String.class));assertFalse(db.queryForObject("SELECT void_error FROM oms_order",String.class).isEmpty());assertEquals(0,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action='VOID_COMPLETE'"));repository.release(COMPANY,1,LEASE);
        Map<String,Object> retry=voidBody(id,1);retry.put("reason","changed retry reason");assertEquals(true,service.request(COMPANY,retry,"second").get(0).get("submitted"));repository.claim(COMPANY,1,LEASE);service.process(COMPANY,id,1,LEASE);assertEquals("VOIDED",db.queryForObject("SELECT status FROM oms_order",String.class));assertEquals("first",db.queryForObject("SELECT void_by FROM oms_order",String.class));assertEquals("test cancellation",db.queryForObject("SELECT void_reason FROM oms_order",String.class));
    }
    @Test void voidRejectsStaleRequestsAndFulfillmentStages(){
        long id=businessOrder();repository.release(COMPANY,1,LEASE);OrderVoidService service=voidService(org.mockito.Mockito.mock(com.oms.common.api.OrderStockClient.class));
        assertEquals(false,service.request(COMPANY,voidBody(id,99),"test").get(0).get("submitted"));Map<String,Object> body=voidBody(id,0);body.put("confirm_local_void",0);assertThrows(IllegalArgumentException.class,()->service.request(COMPANY,body,"test"));
        db.update("UPDATE oms_order SET status='SHIPPED'");assertEquals(false,service.request(COMPANY,voidBody(id,0),"test").get(0).get("submitted"));assertEquals(0,n("SELECT COUNT(*) FROM oms_order_process_log WHERE action='VOID_REQUEST'"));
    }

    Map<String,Object> stockGuard(List<Map<String,Object>> trades){com.oms.order.controller.StockSyncGuardController controller=new com.oms.order.controller.StockSyncGuardController();org.springframework.test.util.ReflectionTestUtils.setField(controller,"db",db);Map<String,Object> body=new LinkedHashMap<>();body.put("channel_id",1);body.put("sku_sn","A");body.put("trades",trades);return controller.guard("VERIFY",body).getData();}
    @Test void stockSyncGuardRejectsStaleAndFailedOrderIntake(){assertEquals(false,stockGuard(Collections.emptyList()).get("can_increase"));db.update("UPDATE oms_order_pull_config SET enabled=1,cursor_time=NOW(),last_error=''");assertEquals(true,stockGuard(Collections.emptyList()).get("can_increase"));db.update("UPDATE oms_order_pull_config SET last_error='query failed'");assertEquals(false,stockGuard(Collections.emptyList()).get("can_increase"));}
    @Test void stockSyncGuardRejectsPlatformSalesNotYetRegistered(){db.update("UPDATE oms_order_pull_config SET enabled=1,cursor_time=NOW(),last_error=''");Map<String,Object> trade=new LinkedHashMap<>();trade.put("tid","123456");trade.put("status","WAIT_SELLER_SEND_GOODS");trade.put("modified","2026-10-19 00:00:00");assertEquals(false,stockGuard(Arrays.asList(trade)).get("can_increase"));}
    @Test void stockSyncGuardRequiresExactPlatformLineAndLatestDemandRegistration(){db.update("UPDATE oms_order_pull_config SET enabled=1,cursor_time=NOW(),last_error=''");long id=exceptionOrder();db.update("UPDATE oms_platform_order SET demand_registered=1,platform_modified='2026-10-19 00:00:00' WHERE id=?",id);db.update("INSERT INTO oms_platform_order_line(company_code,platform_order_id,platform_oid,platform_item_id,platform_sku_id,outer_sku_id,title,quantity,sku_sn,match_error,demand_prepared) VALUES ('VERIFY',?,'1234561','111','222','A','A',2,'A','',1)",id);Map<String,Object> line=new LinkedHashMap<>();line.put("oid","1234561");line.put("item_id","111");line.put("sku_id","222");line.put("quantity",2);Map<String,Object> trade=new LinkedHashMap<>();trade.put("tid","123456");trade.put("status","WAIT_SELLER_SEND_GOODS");trade.put("modified","2026-10-19 00:00:00");trade.put("lines",Arrays.asList(line));assertEquals(true,stockGuard(Arrays.asList(trade)).get("can_increase"));line.put("quantity",3);assertEquals(false,stockGuard(Arrays.asList(trade)).get("can_increase"));}
}
