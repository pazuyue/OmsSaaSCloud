package com.oms.order.service;
import com.oms.common.api.OrderStockClient;
import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static com.oms.order.service.OrderValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderProductMatchServiceTest {
    static AnnotationConfigApplicationContext context;static JdbcTemplate db;static OrderRepository repo;
    OrderProductMatchService service;OrderCatalog catalog;OrderStockClient inventory;long id;String oid="1231",payload=TmallOrderParserTest.payload("25");
    @BeforeAll static void start(){Assumptions.assumeTrue(System.getenv("ORDER_TEST_URL")!=null);context=new AnnotationConfigApplicationContext(OrderRepositoryTest.Config.class);db=context.getBean(JdbcTemplate.class);repo=context.getBean(OrderRepository.class);}
    @AfterAll static void stop(){if(context!=null)context.close();}
    @BeforeEach void setup(){
        for(String t:Arrays.asList("oms_order_process_log","oms_order_line","oms_order","oms_platform_order_line","oms_platform_order","oms_order_pull_task","oms_order_pull_config"))db.update("DELETE FROM "+t);
        db.update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,source_key,seller_nick,initial_time,cursor_time) VALUES ('VERIFY',1,'test','fixture','seller',NOW(),NOW())");
        id=repo.insert("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,source_modified,payload_hash,raw_payload,processing_hash,processing_payload,conversion_status,exception_code,exception_details) VALUES ('VERIFY',1,'123','WAIT_SELLER_SEND_GOODS','fixed','seller',NOW(),?,?,?,?,'EXCEPTION','PRODUCT_INVALID',?)",hash(payload),payload,hash(payload),payload,"[{\"platform_oid\":\"1231\",\"code\":\"SKU_NOT_FOUND\",\"outer_sku_id\":\"A\"}]");
        db.update("INSERT INTO oms_platform_order_line(company_code,platform_order_id,platform_oid,outer_sku_id,title,quantity,match_error) VALUES ('VERIFY',?,'1231','A','原商品',5,'missing')",id);
        catalog=mock(OrderCatalog.class);inventory=mock(OrderStockClient.class);target(Collections.singletonList(new HashMap<>()));when(inventory.demands(anyString(),anyLong(),anyString(),anyString())).thenReturn(R.ok(Collections.emptyList()));
        service=new OrderProductMatchService();ReflectionTestUtils.setField(service,"repository",repo);ReflectionTestUtils.setField(service,"catalog",catalog);ReflectionTestUtils.setField(service,"inventory",inventory);
    }
    void target(List<Map<String,Object>> rows){when(catalog.goods(anyString(),anyList())).thenReturn(Collections.singletonMap("NEW",rows));}
    Map<String,Object> input(){Map<String,Object> input=new HashMap<>(),line=new HashMap<>();input.put("processing_hash",hash(payload));input.put("reason","已核对红色M码");line.put("platform_oid",oid);line.put("sku_sn","NEW");input.put("lines",new ArrayList<>(Arrays.asList(line)));return input;}
    void reject(){assertThrows(IllegalArgumentException.class,()->service.match("VERIFY",id,input(),"test"));assertTrue(repo.manualMatches("VERIFY",id).isEmpty());}
    @Test void savesOrderOnlyAndQueuesExactlyOnce(){
        service.match("VERIFY",id,input(),"operator");Map<String,Object> row=repo.raw("VERIFY",id);assertEquals(payload,row.get("raw_payload"));assertEquals(payload,row.get("processing_payload"));assertEquals("PENDING",row.get("conversion_status"));assertEquals(1,number(row,"retry_count"));
        Map<String,Object> line=db.query("SELECT * FROM oms_platform_order_line",ROW).get(0);assertEquals("A",line.get("outer_sku_id"));assertEquals("NEW",line.get("manual_sku_sn"));assertEquals("operator",line.get("manual_match_by"));assertEquals(0,number(line,"demand_prepared"));assertNull(line.get("sku_sn"));assertNotNull(line.get("manual_match_at"));
        assertThrows(IllegalArgumentException.class,()->service.match("VERIFY",id,input(),"test"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM oms_order_process_log WHERE action='MATCH_PRODUCT'",Integer.class));assertNull(repo.config("VERIFY",1).get("lease_token"));
    }
    @Test void actualDemandAndPreparedMarkerBothBlockChangingSku(){
        when(inventory.demands(anyString(),anyLong(),anyString(),anyString())).thenReturn(R.ok(Arrays.asList(Collections.singletonMap("platform_oid",oid))));reject();
        when(inventory.demands(anyString(),anyLong(),anyString(),anyString())).thenReturn(R.ok(Collections.emptyList()));String lease=token();assertTrue(repo.claim("VERIFY",1,lease));db.update("UPDATE oms_platform_order_line SET sku_sn='A'");repo.prepareDemand("VERIFY",id,lease);db.update("UPDATE oms_platform_order_line SET sku_sn=NULL");repo.release("VERIFY",1,lease);assertEquals(1,db.queryForObject("SELECT demand_prepared FROM oms_platform_order_line",Integer.class));reject();
    }
    @Test void invalidSelectionRollsBackAllLines(){
        Map<String,Object> body=input(),bad=new HashMap<>();bad.put("platform_oid","9999");bad.put("sku_sn","NEW");((List<Map<String,Object>>)body.get("lines")).add(bad);
        assertThrows(IllegalArgumentException.class,()->service.match("VERIFY",id,body,"test"));assertTrue(repo.manualMatches("VERIFY",id).isEmpty());assertEquals(0,number(repo.raw("VERIFY",id),"retry_count"));
    }
    @Test void rejectsForeignStaleChangedAndUnsupportedSource(){
        assertThrows(IllegalArgumentException.class,()->service.match("OTHER",id,input(),"test"));Map<String,Object> stale=input();stale.put("processing_hash",hash("other"));assertThrows(IllegalArgumentException.class,()->service.match("VERIFY",id,stale,"test"));
        db.update("UPDATE oms_platform_order SET change_pending=1");reject();db.update("UPDATE oms_platform_order SET change_pending=0,exception_details=REPLACE(exception_details,'SKU_NOT_FOUND','PRODUCT_UNSUPPORTED')");reject();
    }
    @Test void validatesTargetAndFailsClosedWhenInventoryCannotBeChecked(){
        target(Collections.emptyList());reject();Map<String,Object> target=new HashMap<>();target.put("is_package",1);target(Arrays.asList(target));reject();target.put("is_package",0);target(Arrays.asList(target,target));reject();target(Arrays.asList(target));
        when(inventory.demands(anyString(),anyLong(),anyString(),anyString())).thenThrow(new IllegalStateException("timeout"));assertThrows(IllegalStateException.class,()->service.match("VERIFY",id,input(),"test"));assertTrue(repo.manualMatches("VERIFY",id).isEmpty());assertNull(repo.config("VERIFY",1).get("lease_token"));
    }
    @Test void concurrentSubmissionsHaveOneWinner() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Boolean>> jobs=new ArrayList<>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{start.await();try{service.match("VERIFY",id,input(),"test");return true;}catch(IllegalArgumentException e){return false;}}));start.countDown();int success=0;for(Future<Boolean> job:jobs)if(job.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);assertEquals(1,number(repo.raw("VERIFY",id),"retry_count"));}finally{pool.shutdownNow();}
    }
}
