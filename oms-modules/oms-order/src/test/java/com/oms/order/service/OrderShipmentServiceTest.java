package com.oms.order.service;
import com.oms.common.api.OrderStockClient;
import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.oms.order.service.OrderValues.*;

class OrderShipmentServiceTest {
    static JdbcTemplate db;OrderRepository repository;OrderShipmentService service;long id,platform;OrderBusinessService business;
    Map<String,Object> row(Object...pairs){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)r.put(pairs[i].toString(),pairs[i+1]);return r;}
    @BeforeAll static void setup(){String url=System.getenv("ORDER_TEST_URL");Assumptions.assumeTrue(url!=null&&url.contains("/order_workspace_test?"));if(db!=null&&db.getDataSource() instanceof com.alibaba.druid.pool.DruidDataSource&&!((com.alibaba.druid.pool.DruidDataSource)db.getDataSource()).isClosed())return;com.alibaba.druid.pool.DruidDataSource pool=new com.alibaba.druid.pool.DruidDataSource();pool.setUrl(url);pool.setUsername("root");pool.setPassword(System.getenv("INVENTORY_TEST_PASSWORD"));pool.setInitialSize(1);pool.setMinIdle(1);pool.setMaxActive(8);pool.setMaxWait(10000);db=new JdbcTemplate(pool);}
    @BeforeEach void seed(){
        for(String table:Arrays.asList("oms_order_sales_document_sync","oms_order_shipment_package_line","oms_order_shipment_package","oms_order_shipment_outbound","oms_order_shipment_virtual_outbound","oms_order_shipment_dispatch","oms_order_shipment_log","oms_order_carrier_config","oms_order_carrier","oms_order_shipment_line","oms_order_shipment","oms_order_allocation_config","oms_order_process_log","oms_order_line","oms_order","oms_platform_order_line","oms_platform_order","oms_order_pull_task","oms_order_pull_config"))db.update("DELETE FROM "+table);
        db.update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,source_key,seller_nick,initial_time,cursor_time) VALUES ('VERIFY',1,'shop','source','seller',NOW(),NOW())");
        db.update("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('VERIFY',1,'12345','WAIT_SELLER_SEND_GOODS',NOW(),'hash','{}','SUCCESS')");platform=db.queryForObject("SELECT id FROM oms_platform_order",Long.class);
        db.update("INSERT INTO oms_order(company_code,order_sn,channel_id,platform_order_id,platform_tid,review_status,inventory_status,payment,owner_code,receiver_name,receiver_phone,receiver_address,allocation_policy) VALUES ('VERIFY','SO_TEST',1,?,'12345','APPROVED','RESERVED',12,'','receiver','masked','address',?)",platform,encode(row("owner_code","O1")));id=db.queryForObject("SELECT id FROM oms_order",Long.class);db.update("UPDATE oms_platform_order SET business_order_id=?",id);
        db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,reserved_quantity,payment) VALUES ('VERIFY',?,'123450','A','goods',10,10,12)",id);
        repository=new OrderRepository();ReflectionTestUtils.setField(repository,"jdbc",db);service=new OrderShipmentService(repository);OrderCatalog catalog=mock(OrderCatalog.class);when(catalog.warehouses("VERIFY")).thenReturn(Arrays.asList(row("wms_simulation_code","V1","owner_code","O1","real_store_code","R1","outbound_mode",1),row("wms_simulation_code","V2","owner_code","O1","real_store_code","R2","outbound_mode",1),row("wms_simulation_code","V2","owner_code","O1","real_store_code","R1","outbound_mode",1)));ReflectionTestUtils.setField(service,"catalog",catalog);business=new OrderBusinessService();ReflectionTestUtils.setField(business,"repository",repository);
    }
    Map<String,Object> receipt(long receipt,long qty,String virtual,String real){return row("id",receipt,"company_code","VERIFY","channel_id",1,"platform_tid","12345","platform_oid","123450","sku_sn","A","quantity",qty,"store_code",virtual,"real_store_code",real,"real_store_name",real+" name","owner_code","O1","batch_id",receipt,"stock_source_id",1,"source_type","PUBLIC_EXTRA","status","OCCUPIED");}
    void mirror(List<Map<String,Object>> receipts){long qty=receipts.stream().mapToLong(r->number(r,"quantity")).sum();db.update("UPDATE oms_order SET allocation_quantity=?,allocation_status=?,allocation_receipts=?",qty,qty==10?"FULL":"PART",encode(receipts));}
    List<Map<String,Object>> generate(long version){return service.generate("VERIFY",id,version,"tester");}
    long count(){return db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment",Long.class);}
    Map<String,Object> summary(){return (Map<String,Object>)business.list("VERIFY",null,"","","","","",0,1,20).getRows().get(0);}
    @Test void virtualWarehousesSharingPhysicalWarehouseCreateIndependentDocumentsWithoutPackages(){
        Map<String,Object> a=receipt(1,6,"V1","R1"),b=receipt(2,4,"V2","R1");mirror(Arrays.asList(a,b));
        List<Map<String,Object>> created=generate(0);assertEquals(2,created.size());assertEquals(2,count());
        assertEquals(6,number(created.get(0),"quantity"));assertEquals(4,number(created.get(1),"quantity"));
        assertEquals("V1",created.get(0).get("store_code"));assertEquals("V2",created.get(1).get("store_code"));
        for(Map<String,Object> item:created){Map<String,Object> detail=service.detail("VERIFY",number(item,"id"));
            assertEquals(item.get("store_code"),detail.get("store_code"));assertEquals("WAIT_CARRIER",detail.get("status"));
            assertEquals(0,number(detail,"shipped_quantity"));assertEquals(0,number(detail,"package_count"));
            assertEquals(1,((List<?>)detail.get("lines")).size());}
        assertEquals(10,number(summary(),"shipment_quantity"));assertEquals(0,number(summary(),"pending_allocation_quantity"));
        assertTrue(generate(1).isEmpty());assertEquals(2,count());assertThrows(IllegalArgumentException.class,()->generate(0));
    }
    @Test void batchesOfSameVirtualWarehouseStayInOneDocumentAndSupplementCreatesNewWave(){
        Map<String,Object> a=receipt(1,2,"V1","R1"),b=receipt(2,4,"V1","R1"),c=receipt(3,4,"V1","R1");mirror(Arrays.asList(a,b));
        List<Map<String,Object>> first=generate(0);assertEquals(1,first.size());assertEquals(6,number(first.get(0),"quantity"));
        assertEquals(2,((List<?>)service.detail("VERIFY",number(first.get(0),"id")).get("lines")).size());
        mirror(Arrays.asList(a,b,c));List<Map<String,Object>> next=generate(1);assertEquals(1,next.size());assertEquals(4,number(next.get(0),"quantity"));
        assertEquals(6,number(service.detail("VERIFY",number(first.get(0),"id")),"quantity"));assertEquals(2,count());assertTrue(generate(2).isEmpty());
    }

    @Test void multiplePhysicalWarehousesAndSupplementCreateNewDocumentsWithoutChangingFirstWave(){
        Map<String,Object> a=receipt(1,3,"V1","R1"),b=receipt(2,3,"V2","R2"),c=receipt(3,4,"V1","R1");mirror(Arrays.asList(a,b));assertEquals(2,generate(0).size());List<Map<String,Object>> original=db.query("SELECT shipment_sn,quantity,generation_no FROM oms_order_shipment ORDER BY id",ROW);
        mirror(Arrays.asList(a,b,c));assertEquals(1,generate(1).size());assertEquals(3,count());assertEquals(original,db.query("SELECT shipment_sn,quantity,generation_no FROM oms_order_shipment WHERE generation_no=1 ORDER BY id",ROW));assertEquals(10,db.queryForObject("SELECT SUM(quantity) FROM oms_order_shipment_line",Integer.class));assertEquals(2,db.queryForObject("SELECT MAX(generation_no) FROM oms_order_shipment",Integer.class));assertTrue(generate(2).isEmpty());assertEquals(0,number(summary(),"pending_allocation_quantity"));
    }
    @Test void duplicatedOversizedWrongOrderAndReleasedReceiptsNeverCreateDocuments(){
        Map<String,Object> a=receipt(1,6,"V1","R1");mirror(Arrays.asList(a,a));assertThrows(IllegalArgumentException.class,()->generate(0));assertEquals(0,count());
        a.put("quantity",11);mirror(Arrays.asList(a));assertThrows(IllegalArgumentException.class,()->generate(0));
        a.put("quantity",6);a.put("platform_tid","other");mirror(Arrays.asList(a));assertThrows(IllegalArgumentException.class,()->generate(0));
        a.put("platform_tid","12345");a.put("status","RELEASED");mirror(Arrays.asList(a));assertThrows(IllegalArgumentException.class,()->generate(0));assertEquals(0,count());
    }
    @Test void changedOrMissingPreviouslyAssignedReceiptsAreRejected(){
        Map<String,Object> a=receipt(1,6,"V1","R1"),b=receipt(2,4,"V2","R2");mirror(Arrays.asList(a));generate(0);
        a.put("quantity",5);mirror(Arrays.asList(a));assertThrows(IllegalArgumentException.class,()->generate(1));
        mirror(Arrays.asList(b));assertThrows(IllegalArgumentException.class,()->generate(1));assertEquals(1,count());assertEquals(6,db.queryForObject("SELECT quantity FROM oms_order_shipment",Integer.class));
    }
    @Test void anyGroupFailureRollsBackEveryNewDocumentAndLine(){
        Map<String,Object> a=receipt(1,2,"V1","R1"),b=receipt(2,2,"V2","R2"),c=receipt(3,2,"V2","R2");c.put("real_store_name","different name");mirror(Arrays.asList(a,b,c));assertThrows(IllegalArgumentException.class,()->generate(0));assertEquals(0,count());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment_line",Integer.class));assertEquals(0,db.queryForObject("SELECT control_version FROM oms_order",Integer.class));
    }
    @Test void concurrentGenerationHasOneWinnerAndNoDuplicateQuantity() throws Exception {
        mirror(Arrays.asList(receipt(1,6,"V1","R1")));java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try{List<java.util.concurrent.Future<Boolean>> jobs=new ArrayList<>();for(int n=0;n<2;n++)jobs.add(pool.submit(()->{start.await();try{generate(0);return true;}catch(IllegalArgumentException e){return false;}}));start.countDown();int winners=0;for(java.util.concurrent.Future<Boolean> job:jobs)if(job.get(10,java.util.concurrent.TimeUnit.SECONDS))winners++;assertEquals(1,winners);}finally{pool.shutdownNow();}assertEquals(1,count());assertEquals(6,db.queryForObject("SELECT SUM(quantity) FROM oms_order_shipment_line",Integer.class));
    }
    @Test void queriesAreCompanyScopedFilterableAndDoNotExposeReceiverInList(){
        mirror(Arrays.asList(receipt(1,6,"V1","R1")));long shipment=number(generate(0).get(0),"id");assertEquals(1,service.list("VERIFY","12345",1L,"WAIT_CARRIER","R1","A",1,20).getTotal());assertEquals(0,service.list("VERIFY","",null,"SHIPPED","","",1,20).getTotal());assertEquals(0,service.list("OTHER","",null,"","","",1,20).getTotal());assertThrows(IllegalArgumentException.class,()->service.detail("OTHER",shipment));assertThrows(IllegalArgumentException.class,()->service.generate("OTHER",id,1,"test"));assertFalse(((Map<?,?>)service.list("VERIFY","",null,"","","",1,20).getRows().get(0)).containsKey("receiver_phone"));
        db.update("UPDATE oms_order SET manual_hold=1");assertThrows(IllegalArgumentException.class,()->generate(1));
    }
    OrderVoidService voids(OrderStockClient stock){OrderVoidService service=new OrderVoidService();ReflectionTestUtils.setField(service,"repository",repository);ReflectionTestUtils.setField(service,"inventory",stock);return service;}
    Map<String,Object> voidRequest(){return row("orders",Arrays.asList(row("id",id,"control_version",1)),"reason","cancel","confirm_local_void",1);}
    @Test void cancellationFreezesDocumentsAndOnlyConfirmedInventoryReleaseCompletesVoid(){
        mirror(Arrays.asList(receipt(1,6,"V1","R1")));generate(0);OrderStockClient stock=mock(OrderStockClient.class);OrderVoidService cancel=voids(stock);assertEquals(true,cancel.request("VERIFY",voidRequest(),"test").get(0).get("submitted"));assertEquals("VOIDING",db.queryForObject("SELECT status FROM oms_order_shipment",String.class));
        db.update("UPDATE oms_order_pull_config SET lease_token='lease',lease_until=DATE_ADD(NOW(),INTERVAL 10 MINUTE)");when(stock.execute(anyString(),anyString(),any(),anyString())).thenThrow(new IllegalStateException("timeout"));cancel.process("VERIFY",id,1,"lease");assertEquals("VOIDING",db.queryForObject("SELECT status FROM oms_order_shipment",String.class));assertNull(db.queryForObject("SELECT cancelled_at FROM oms_order_shipment",java.sql.Timestamp.class));
        doReturn(R.ok(Arrays.asList(row("platform_oid","123450","sku_sn","A","status","CANCELLED","reserved_quantity",0,"demand_quantity",10)))).when(stock).execute(anyString(),anyString(),any(),anyString());cancel.process("VERIFY",id,1,"lease");assertEquals("VOIDED",db.queryForObject("SELECT status FROM oms_order_shipment",String.class));assertNotNull(db.queryForObject("SELECT cancelled_at FROM oms_order_shipment",java.sql.Timestamp.class));assertEquals(0,number(summary(),"shipment_quantity"));assertEquals(0,number(summary(),"pending_allocation_quantity"));assertEquals(0,number(summary(),"shipped_quantity"));assertEquals(6,db.queryForObject("SELECT quantity FROM oms_order_shipment",Integer.class));
    }
    @Test void warehouseProcessingOrShippedQuantityBlocksLocalWholeOrderVoid(){
        mirror(Arrays.asList(receipt(1,6,"V1","R1")));generate(0);OrderVoidService cancel=voids(mock(OrderStockClient.class));db.update("UPDATE oms_order_shipment SET status='WAREHOUSE_PROCESSING'");assertEquals(false,cancel.request("VERIFY",voidRequest(),"test").get(0).get("submitted"));assertEquals(false,summary().get("can_void"));assertEquals("WAIT_ALLOCATION",db.queryForObject("SELECT status FROM oms_order",String.class));
        db.update("UPDATE oms_order_shipment SET status='WAIT_CARRIER'");db.update("UPDATE oms_order_shipment_line SET shipped_quantity=1");assertEquals(false,cancel.request("VERIFY",voidRequest(),"test").get(0).get("submitted"));assertEquals(false,summary().get("can_void"));assertEquals(1,number(summary(),"shipped_quantity"));
    }
}
