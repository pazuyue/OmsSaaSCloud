package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.oms.order.service.OrderValues.*;

class OrderAllocationServiceTest {
    static JdbcTemplate db;OrderAllocationService service;OrderCatalog catalog;OrderWarehouseClient warehouse;long id;
    Map<String,Object> row(Object...pairs){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)r.put(pairs[i].toString(),pairs[i+1]);return r;}
    @BeforeAll static void setup(){String url=System.getenv("ORDER_TEST_URL");Assumptions.assumeTrue(url!=null&&url.contains("/order_workspace_test?"));db=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD")));}
    Map<String,Object> config(){return row("enabled",1,"owner_code","O1","allow_multi",1,"allow_partial",1,"allow_split_sku",0,"warehouse_order",Arrays.asList("V1"),"province_priorities",Collections.singletonMap("北京",Arrays.asList("V1")));}
    @BeforeEach void seed(){
        for(String table:Arrays.asList("oms_order_shipment_line","oms_order_shipment","oms_order_allocation_config","oms_order_process_log","oms_order_line","oms_order","oms_platform_order_line","oms_platform_order","oms_order_pull_task","oms_order_pull_config"))db.update("DELETE FROM "+table);
        db.update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,source_key,seller_nick,initial_time,cursor_time) VALUES ('VERIFY',1,'shop','source','seller',NOW(),NOW())");
        db.update("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,source_modified,payload_hash,raw_payload,processing_payload,conversion_status) VALUES ('VERIFY',1,'12345','WAIT_SELLER_SEND_GOODS',NOW(),'hash','{}',?,'SUCCESS')","{\"trade_fullinfo_get_response\":{\"trade\":{\"receiver_state\":\"北京市\"}}}");
        long platform=db.queryForObject("SELECT id FROM oms_platform_order",Long.class);
        db.update("INSERT INTO oms_order(company_code,order_sn,channel_id,platform_order_id,platform_tid,review_status,inventory_status,payment,owner_code) VALUES ('VERIFY','SO_TEST',1,?,'12345','APPROVED','RESERVED',0,'')",platform);
        id=db.queryForObject("SELECT id FROM oms_order",Long.class);db.update("UPDATE oms_platform_order SET business_order_id=?",id);
        db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,reserved_quantity,payment) VALUES ('VERIFY',?,'123450','A','A',10,10,0)",id);
        db.update("INSERT INTO oms_order_allocation_config(company_code,channel_id,policy_json,updated_by) VALUES ('VERIFY',1,?,'test')",encode(config()));
        OrderRepository repository=new OrderRepository();ReflectionTestUtils.setField(repository,"jdbc",db);catalog=mock(OrderCatalog.class);warehouse=mock(OrderWarehouseClient.class);
        when(catalog.warehouses("VERIFY")).thenReturn(Arrays.asList(row("wms_simulation_code","V1","owner_code","O1","real_store_code","R1","real_store_name","warehouse")));
        when(catalog.validateShop("VERIFY",1)).thenReturn(row("channel_name","shop"));when(warehouse.receipts(anyString(),anyLong(),anyString(),anyString())).thenReturn(R.ok(Collections.emptyList()));
        service=new OrderAllocationService();ReflectionTestUtils.setField(service,"repository",repository);ReflectionTestUtils.setField(service,"catalog",catalog);ReflectionTestUtils.setField(service,"warehouse",warehouse);
    }
    Map<String,Object> request(long version){return row("items",Arrays.asList(row("id",id,"control_version",version)));}
    @SuppressWarnings("unchecked") @Test void previewAndExecutionCountOneShipmentAcrossPurchasedAndFourGiftScopes(){
        for(int i=1;i<=4;i++)db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,reserved_quantity,payment,gift_plan_id,stock_reference) VALUES ('VERIFY',?,'','A','gift',1,1,0,77,?)",id,"GIFT:77:"+i);
        Map<String,Object> policy=config();policy.put("max_shipments",1);db.update("UPDATE oms_order_allocation_config SET policy_json=?",encode(policy));
        java.util.function.Function<Map<String,Object>,List<Map<String,Object>>> planned=command->{String reference=text(command,"platform_tid");Map<String,Object> r=new LinkedHashMap<>(receipts(5).get(0));r.put("platform_tid",reference);r.put("id",reference.equals("12345")?100:100+Long.parseLong(reference.substring(reference.lastIndexOf(':')+1)));r.put("platform_oid",reference.equals("12345")?"123450":"");r.put("quantity",reference.equals("12345")?10:1);return Arrays.asList(r);};
        when(warehouse.preview(anyString(),anyMap(),anyString())).thenAnswer(call->{Map<String,Object> command=call.getArgument(1);List<Map<String,Object>> lines=planned.apply(command);return R.ok(row("can_allocate",true,"plan_fingerprint","P","required_quantity",number(lines.get(0),"quantity"),"planned_quantity",number(lines.get(0),"quantity"),"remaining_quantity",0,"new_shipment_count",1,"existing_shipment_count",0,"lines",lines));});
        when(warehouse.allocate(anyString(),anyMap(),anyString())).thenAnswer(call->R.ok(planned.apply(call.getArgument(1))));
        Map<String,Object> preview=service.preview("VERIFY",request(0)).get(0);assertEquals(true,preview.get("can_allocate"));assertEquals(1,number(preview,"new_shipment_count"));assertEquals(14,number(preview,"planned_quantity"));
        Map<String,Object> input=request(0);((List<Map<String,Object>>)input.get("items")).get(0).put("plan_fingerprint",preview.get("plan_fingerprint"));Map<String,Object> result=service.allocate("VERIFY",input,"tester").get(0);assertEquals(true,result.get("success"),result.toString());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment",Integer.class));assertEquals(14,db.queryForObject("SELECT quantity FROM oms_order_shipment",Integer.class));
    }
    @Test void settingsListIncludesUnconfiguredShopsWithDisabledDefaultsAndCompanyIsolation(){
        db.update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,source_key,seller_nick,initial_time,cursor_time) VALUES ('VERIFY',2,'second','source','other',NOW(),NOW())");
        List<Map<String,Object>> rows=service.configurations("VERIFY");assertEquals(2,rows.size());assertEquals(true,rows.get(0).get("configured"));assertEquals(false,rows.get(1).get("configured"));assertEquals(0,number((Map<String,Object>)rows.get(1).get("policy"),"enabled"));assertFalse(rows.get(0).containsKey("policy_json"));assertFalse(rows.get(0).containsKey("source_key"));assertTrue(service.configurations("OTHER").isEmpty());verifyNoInteractions(catalog,warehouse);
    }
    List<Map<String,Object>> receipts(long qty){List<Map<String,Object>> receipts=new ArrayList<>();receipts.add(row("id",101,"platform_oid","123450","owner_code","O1","batch_id",1,"stock_source_id",1,"source_type","PUBLIC_EXTRA","quantity",Math.min(qty,5),"store_code","V1","real_store_code","R1","real_store_name","warehouse","sku_sn","A"));if(qty>5)receipts.add(row("id",102,"platform_oid","123450","owner_code","O1","batch_id",2,"stock_source_id",1,"source_type","PUBLIC_EXTRA","quantity",qty-5,"store_code","V1","real_store_code","R1","real_store_name","warehouse","sku_sn","A"));return receipts;}
    @Test void successfulPartialThenFullMirrorsReceiptsAndPinsOriginalRules(){
        when(warehouse.allocate(anyString(),anyMap(),anyString())).thenReturn(R.ok(receipts(5)));assertEquals(true,service.allocate("VERIFY",request(0),"tester").get(0).get("success"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment",Integer.class));assertEquals(5,db.queryForObject("SELECT SUM(quantity) FROM oms_order_shipment",Integer.class));
        assertEquals("PART",db.queryForObject("SELECT allocation_status FROM oms_order",String.class));assertEquals(1,db.queryForObject("SELECT control_version FROM oms_order",Integer.class));
        Map<String,Object> changed=config();changed.put("allow_partial",0);db.update("UPDATE oms_order_allocation_config SET policy_json=?",encode(changed));
        when(warehouse.allocate(anyString(),anyMap(),anyString())).thenAnswer(call->{Map<String,Object> command=call.getArgument(1);assertEquals(1L,number(command,"allow_partial"));assertEquals("北京市",command.get("receiver_province"));return R.ok(receipts(10));});
        assertEquals(true,service.allocate("VERIFY",request(1),"tester").get(0).get("success"));assertEquals("FULL",db.queryForObject("SELECT allocation_status FROM oms_order",String.class));assertEquals(10,db.queryForObject("SELECT allocation_quantity FROM oms_order",Integer.class));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment",Integer.class));assertEquals(10,db.queryForObject("SELECT SUM(quantity) FROM oms_order_shipment",Integer.class));
    }
    @Test void timeoutLeavesDurableIntentAndRetryCanConfirmExistingOccupation(){
        when(warehouse.allocate(anyString(),anyMap(),anyString())).thenThrow(new IllegalStateException("timeout"));assertEquals(false,service.allocate("VERIFY",request(0),"tester").get(0).get("success"));assertEquals("UNCERTAIN",db.queryForObject("SELECT allocation_status FROM oms_order",String.class));assertNotNull(db.queryForObject("SELECT allocation_policy FROM oms_order",String.class));
        when(warehouse.receipts(anyString(),anyLong(),anyString(),anyString())).thenReturn(R.ok(receipts(10)));doReturn(R.ok(receipts(10))).when(warehouse).allocate(anyString(),anyMap(),anyString());
        assertEquals(true,service.allocate("VERIFY",request(0),"tester").get(0).get("success"));assertEquals("FULL",db.queryForObject("SELECT allocation_status FROM oms_order",String.class));
    }
    @Test void shipmentFailureRollsBackLocalAllocationMirrorAndRetryCreatesOnlyConfirmedReceipts(){
        List<Map<String,Object>> invalid=receipts(5);invalid.get(0).put("sku_sn","WRONG");when(warehouse.allocate(anyString(),anyMap(),anyString())).thenReturn(R.ok(invalid));
        assertEquals(false,service.allocate("VERIFY",request(0),"tester").get(0).get("success"));assertEquals(0,db.queryForObject("SELECT allocation_quantity FROM oms_order",Integer.class));assertEquals(0,db.queryForObject("SELECT control_version FROM oms_order",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment_line",Integer.class));
        when(warehouse.allocate(anyString(),anyMap(),anyString())).thenReturn(R.ok(receipts(5)));assertEquals(true,service.allocate("VERIFY",request(0),"tester").get(0).get("success"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment",Integer.class));assertEquals(5,db.queryForObject("SELECT SUM(quantity) FROM oms_order_shipment_line",Integer.class));
    }
    @Test void holdsReviewAndCompanyIsolationPreventInventoryCalls(){
        db.update("UPDATE oms_order SET manual_hold=1");assertEquals(false,service.allocate("VERIFY",request(0),"tester").get(0).get("success"));verify(warehouse,never()).allocate(anyString(),anyMap(),anyString());
        db.update("UPDATE oms_order SET manual_hold=0,review_status='PENDING'");assertEquals(false,service.allocate("VERIFY",request(0),"tester").get(0).get("success"));
        assertEquals(false,service.allocate("OTHER",request(0),"tester").get(0).get("success"));assertEquals(0,db.queryForObject("SELECT allocation_quantity FROM oms_order",Integer.class));
    }
    @Test void ruleSaveUsesIndependentRevisionAndValidatesOwner(){
        Map<String,Object> input=row("channel_id",1,"revision",1,"policy",config());service.save("VERIFY",input,"tester");assertThrows(IllegalArgumentException.class,()->service.save("VERIFY",input,"tester"));
        Map<String,Object> invalid=config();invalid.put("owner_code","OTHER");input.put("revision",2);input.put("policy",invalid);assertThrows(IllegalArgumentException.class,()->service.save("VERIFY",input,"tester"));
    }

    @Test void previewDoesNotPersistIntentAndChangedRulesRequireNewPreview(){when(warehouse.preview(anyString(),anyMap(),anyString())).thenReturn(R.ok(row("can_allocate",true,"plan_fingerprint","preview")));Map<String,Object> preview=service.preview("VERIFY",request(0)).get(0);assertEquals(true,preview.get("can_allocate"));assertEquals("NONE",db.queryForObject("SELECT allocation_status FROM oms_order",String.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oms_order_shipment",Integer.class));verify(warehouse,never()).allocate(anyString(),anyMap(),anyString());db.update("UPDATE oms_order_allocation_config SET revision=revision+1");Map<String,Object> item=(Map<String,Object>)((List<?>)request(0).get("items")).get(0);item.put("policy_fingerprint",preview.get("policy_fingerprint"));assertEquals(false,service.allocate("VERIFY",row("items",Arrays.asList(item)),"test").get(0).get("success"));verify(warehouse,never()).allocate(anyString(),anyMap(),anyString());}
    @Test void previewKeepsCompanyReviewAndHoldGates(){db.update("UPDATE oms_order SET manual_hold=1");assertEquals(false,service.preview("VERIFY",request(0)).get(0).get("can_allocate"));assertEquals(false,service.preview("OTHER",request(0)).get(0).get("can_allocate"));verify(warehouse,never()).preview(anyString(),anyMap(),anyString());}
    @Test void committedRemoteReceiptsKeepOriginalRuleEvenBeforeLocalMirrorIsRestored(){when(warehouse.allocate(anyString(),anyMap(),anyString())).thenThrow(new IllegalStateException("timeout"));assertEquals(false,service.allocate("VERIFY",request(0),"test").get(0).get("success"));Map<String,Object> changed=config();changed.put("strategy","REGION_FIRST");db.update("UPDATE oms_order_allocation_config SET policy_json=?,revision=revision+1",encode(changed));when(warehouse.receipts(anyString(),anyLong(),anyString(),anyString())).thenReturn(R.ok(receipts(10)));when(warehouse.preview(anyString(),anyMap(),anyString())).thenReturn(R.ok(row("can_allocate",true,"recovered_quantity",10)));Map<String,Object> preview=service.preview("VERIFY",request(0)).get(0);assertEquals(true,preview.get("using_snapshot"));assertEquals("FEWEST_SHIPMENTS",((Map<?,?>)preview.get("policy")).get("strategy"));}
}
