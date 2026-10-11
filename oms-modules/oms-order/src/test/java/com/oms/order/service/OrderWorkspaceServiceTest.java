package com.oms.order.service;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderWorkspaceServiceTest {
    static JdbcTemplate db;OrderWorkspaceService workspace;OrderCatalog catalog;ConfiguredPushReader push;
    @BeforeAll static void setup(){String url=System.getenv("ORDER_TEST_URL");Assumptions.assumeTrue(url!=null&&url.contains("/order_workspace_test?"));db=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD")));}
    @BeforeEach void reset(){
        for(String t:Arrays.asList("oms_order_process_log","oms_order_line","oms_order","oms_platform_order_line","oms_platform_order","oms_order_pull_task","oms_order_pull_config","oms_order_push_source"))db.update("DELETE FROM "+t);
        for(String key:Arrays.asList("one","two"))db.update("INSERT INTO oms_order_push_source(company_code,source_key,source_name,host,port,database_name,username,password_cipher,enabled) VALUES ('VERIFY',?,?,'127.0.0.1',13306,'test','test','test',1)",key,key);
        OrderRepository repository=new OrderRepository();ReflectionTestUtils.setField(repository,"jdbc",db);
        catalog=mock(OrderCatalog.class);Map<String,Object> shop=new HashMap<>();shop.put("channel_name","shop");shop.put("seller_nick","seller");when(catalog.validateShop("VERIFY",1)).thenReturn(shop);
        push=mock(ConfiguredPushReader.class);when(push.configured("VERIFY","one")).thenReturn(true);when(push.configured("VERIFY","two")).thenReturn(true);
        workspace=new OrderWorkspaceService();ReflectionTestUtils.setField(workspace,"repository",repository);ReflectionTestUtils.setField(workspace,"catalog",catalog);ReflectionTestUtils.setField(workspace,"push",push);
    }
    Map<String,Object> input(){Map<String,Object> v=new HashMap<>();v.put("review_mode","AUTO");v.put("manual_match_review",1);v.put("channel_id",1);v.put("allowed_trade_types",Arrays.asList("fixed","guarantee_trade","tmall_i18n"));v.put("source_key","one");v.put("seller_nick","seller");v.put("enabled",0);v.put("auto_convert",0);v.put("allow_public_stock",0);v.put("interval_seconds",30);v.put("initial_time","2026-10-01 00:00:00");v.put("revision",0);return v;}
    @Test void pausedConfigurationSavesWithoutOwnerOrWarehouses(){workspace.save("VERIFY",input(),"test");Map<String,Object> row=db.query("SELECT * FROM oms_order_pull_config",OrderValues.ROW).get(0);assertNull(row.get("owner_code"));assertNull(row.get("store_codes"));assertFalse(workspace.configs("VERIFY").get(0).containsKey("owner_code"));assertFalse(workspace.configs("VERIFY").get(0).containsKey("store_codes"));}
    @Test void normalEditPreservesProgressAndRejectsStaleRevision(){Map<String,Object> v=input();workspace.save("VERIFY",v,"test");db.update("UPDATE oms_order_pull_config SET cursor_time='2026-10-03 00:00:00'");v.put("revision",1);v.put("interval_seconds",60);workspace.save("VERIFY",v,"test");assertEquals("2026-10-03 00:00:00",OrderValues.text(db.query("SELECT * FROM oms_order_pull_config",OrderValues.ROW).get(0),"cursor_time"));assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));}
    @Test void switchingSourceRequiresAcknowledgedCursorResetAndNoPendingTasks(){Map<String,Object> v=input();workspace.save("VERIFY",v,"test");v.put("revision",1);v.put("source_key","two");v.put("initial_time","2026-10-02 00:00:00");assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));v.put("reset_cursor",1);db.update("INSERT INTO oms_order_pull_task(company_code,channel_id,task_type,start_time,end_time,page_time,operator_name) VALUES ('VERIFY',1,'MANUAL',NOW(),NOW(),NOW(),'test')");assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));db.update("UPDATE oms_order_pull_task SET status='SUCCESS'");workspace.save("VERIFY",v,"test");Map<String,Object> row=db.query("SELECT * FROM oms_order_pull_config",OrderValues.ROW).get(0);assertEquals("two",row.get("source_key"));assertEquals("2026-10-02 00:00:00",OrderValues.text(row,"cursor_time"));}
    @Test void pullAndConversionSwitchesCanBeEnabledIndependently(){
        Map<String,Object> v=input();v.put("enabled",1);workspace.save("VERIFY",v,"test");
        assertEquals(1,db.queryForObject("SELECT enabled FROM oms_order_pull_config",Integer.class));
        assertEquals(0,db.queryForObject("SELECT auto_convert FROM oms_order_pull_config",Integer.class));
        v.put("revision",1);v.put("enabled",0);v.put("auto_convert",1);workspace.save("VERIFY",v,"test");
        assertEquals(0,db.queryForObject("SELECT enabled FROM oms_order_pull_config",Integer.class));
        assertEquals(1,db.queryForObject("SELECT auto_convert FROM oms_order_pull_config",Integer.class));
        assertEquals(1,workspace.backfill("VERIFY",Arrays.asList(1L),"2026-10-01 00:00:00","2026-10-02 00:00:00","test").size());
    }
    @Test void sourceMustStillBeEnabledWhenTransactionSavesReference(){db.update("UPDATE oms_order_push_source SET enabled=0 WHERE source_key='one'");assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",input(),"test"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oms_order_pull_config",Integer.class));}
    @Test void publicSupplementPolicyCanBeSavedAndChanged(){
        Map<String,Object> v=input();v.put("allow_public_stock",1);workspace.save("VERIFY",v,"test");
        assertEquals(1,OrderValues.number(workspace.configs("VERIFY").get(0),"allow_public_stock"));
        v.put("revision",1);v.put("allow_public_stock",0);workspace.save("VERIFY",v,"test");
        assertEquals(0,OrderValues.number(workspace.configs("VERIFY").get(0),"allow_public_stock"));
        v.put("revision",2);v.put("allow_public_stock",2);assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));
    }
    @Test void tradeTypeSelectionRoundTripsAndRejectsInvalidInput(){
        Map<String,Object> v=input();v.put("allowed_trade_types",Arrays.asList("tmall_i18n","custom_trade"));workspace.save("VERIFY",v,"test");
        assertEquals(Arrays.asList("tmall_i18n","custom_trade"),workspace.configs("VERIFY").get(0).get("allowed_trade_types"));
        v.put("revision",1);v.put("allowed_trade_types",Arrays.asList("fixed"));workspace.save("VERIFY",v,"test");
        assertEquals(Arrays.asList("fixed"),workspace.configs("VERIFY").get(0).get("allowed_trade_types"));
        v.put("revision",2);v.put("allowed_trade_types",Collections.emptyList());assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));
        v.put("allowed_trade_types",Arrays.asList("*"));assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));
    }
    @Test void differentNameRequiresConfirmationOncePerMapping(){
        Map<String,Object> v=input();v.put("seller_nick","different seller");
        assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));
        v.put("confirm_seller_mapping",1);workspace.save("VERIFY",v,"test");
        v.put("revision",1);v.put("confirm_seller_mapping",0);v.put("auto_convert",1);workspace.save("VERIFY",v,"test");
        assertEquals("different seller",db.queryForObject("SELECT seller_nick FROM oms_order_pull_config",String.class));
        v.put("revision",2);v.put("source_key","two");v.put("reset_cursor",1);
        assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));
    }
    @Test void duplicateBindingIsRejectedEvenWhenOriginalIsPaused(){
        workspace.save("VERIFY",input(),"test");Map<String,Object> second=new HashMap<>();second.put("seller_nick","seller");second.put("channel_name","second shop");when(catalog.validateShop("VERIFY",2)).thenReturn(second);
        Map<String,Object> v=input();v.put("channel_id",2);
        assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM oms_order_pull_config",Integer.class));
        v.put("source_key","two");workspace.save("VERIFY",v,"test");
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM oms_order_pull_config",Integer.class));
    }
    @Test void concurrentBindingsHaveOneWinner() throws Exception {
        Map<String,Object> second=new HashMap<>();second.put("seller_nick","seller");second.put("channel_name","second shop");when(catalog.validateShop("VERIFY",2)).thenReturn(second);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> jobs=new ArrayList<>();
        try{for(int channel:Arrays.asList(1,2))jobs.add(pool.submit(()->{Map<String,Object> v=input();v.put("channel_id",channel);start.await();try{workspace.save("VERIFY",v,"test");return true;}catch(IllegalArgumentException e){return false;}}));start.countDown();int successes=0;for(java.util.concurrent.Future<Boolean> job:jobs)if(job.get(10,java.util.concurrent.TimeUnit.SECONDS))successes++;assertEquals(1,successes);}finally{pool.shutdownNow();}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM oms_order_pull_config",Integer.class));
    }
    @Test void existingOrdersRetainSellerAndOwnership(){
        workspace.save("VERIFY",input(),"test");
        db.update("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,seller_nick,source_modified,payload_hash,raw_payload) VALUES ('VERIFY',1,'998877','seller',NOW(),'test','{}')");
        Map<String,Object> v=input();v.put("revision",1);v.put("seller_nick","another seller");v.put("reset_cursor",1);v.put("confirm_seller_mapping",1);
        assertThrows(IllegalArgumentException.class,()->workspace.save("VERIFY",v,"test"));
        assertEquals("seller",db.queryForObject("SELECT seller_nick FROM oms_order_pull_config",String.class));
        assertEquals(1,db.queryForObject("SELECT channel_id FROM oms_platform_order",Integer.class));
    }
    @Test void emptySourcePreviewDoesNotCreateTasksOrBlockSaving(){
        Map<String,Object> v=input();v.put("start_time","2026-10-01 00:00:00");v.put("end_time","2026-10-02 00:00:00");
        when(push.preview(eq("VERIFY"),eq("one"),eq("seller"),anyMap())).thenReturn(Collections.emptyList());
        assertEquals(Collections.emptyList(),workspace.verifySource("VERIFY",v).get("orders"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oms_order_pull_task",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oms_platform_order",Integer.class));
        workspace.save("VERIFY",v,"test");
        assertEquals("2026-10-01 00:00:00",OrderValues.text(workspace.configs("VERIFY").get(0),"cursor_time"));
        v.put("start_time","2026-01-01 00:00:00");assertThrows(IllegalArgumentException.class,()->workspace.verifySource("VERIFY",v));
    }
    @Test void previewFailureDoesNotExposeConnectionDetails(){
        Map<String,Object> v=input();v.put("start_time","2026-10-01 00:00:00");v.put("end_time","2026-10-02 00:00:00");
        when(push.preview(anyString(),anyString(),anyString(),anyMap())).thenThrow(new IllegalStateException("secret jdbc details"));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->workspace.verifySource("VERIFY",v));
        assertFalse(error.getMessage().contains("secret"));assertTrue(error.getMessage().contains("来源验证失败"));
    }
}

