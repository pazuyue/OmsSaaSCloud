package com.oms.supplychain;

import com.oms.supplychain.service.wms.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static com.oms.supplychain.PurchaseWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SalesOutboundDocumentServiceTest {
    SalesOutboundDocumentService sales;
    @BeforeAll static void setup(){PurchaseWorkspaceTest.init();}
    @AfterAll static void close(){PurchaseWorkspaceTest.shutdown();}
    @BeforeEach void seed(){for(String t:Arrays.asList("wms_sales_outbound_movement","wms_sales_outbound_document","wms_sales_outbound_lock"))db.update("DELETE FROM "+t);new PurchaseWorkspaceTest().seed();sales=new SalesOutboundDocumentService();ReflectionTestUtils.setField(sales,"db",context.getBean(WmsStore.class));}
    Map<String,Object> line(long id,String store,long qty,long shipped){return m("id",id,"sku_sn","SKU-"+id,"goods_name","销售商品","store_code",store,"batch_id",100+id,"batch_code","B-"+id,"quantity",qty,"shipped_quantity",shipped);}
    Map<String,Object> movement(long line,long qty,String req,String mode){return m("id",line,"quantity",qty,"batch_id",100+line,"request_id",req,"source_mode",mode,"created_by","tester","outbound_time","2026-10-12 12:00:00");}
    Map<String,Object> body(long version,long shipped){return m("id",1,"source_version",version,"shipment_sn","XS_TEST","order_id",9,"order_sn","SO_TEST","platform_tid","12345","channel_id",1,"channel_name","测试店铺","owner_code","O","real_store_code","W","status",shipped==8?"SHIPPED":shipped>0?"PART_SHIPPED":"WAIT_DISPATCH","dispatch_state","NONE","external_order","","warehouse_status","","last_error","","created_by","tester","lines",Arrays.asList(line(1,"V",8,shipped)),"movements",shipped==0?Collections.emptyList():Arrays.asList(movement(1,shipped,"REQ-1","VIRTUAL")));}
    long ticket(){return db.queryForObject("SELECT ticket_id FROM wms_sales_outbound_document WHERE company_code='QM'",Long.class);}
    @Test void plannedThenPartialThenFullCreatesXsDocumentAndEveryMovementWithoutInventoryCalls(){
        sales.sync("qm",body(1,0));assertTrue(db.queryForObject("SELECT sn FROM wms_tickets WHERE id=?",String.class,ticket()).startsWith("XS"));
        sales.sync("QM",body(2,4));Map<String,Object> partial=service.detail("QM","ticket",ticket());assertEquals("SALE_OUT",partial.get("documentType"));assertEquals("部分出库",partial.get("progressLabel"));assertEquals(4L,((Number)partial.get("numberPosted")).longValue());assertEquals(4L,((Number)partial.get("remainingQuantity")).longValue());assertFalse(partial.containsKey("purchaseId"));
        Map<String,Object> full=body(3,8);full.put("movements",Arrays.asList(movement(1,4,"REQ-1","VIRTUAL"),movement(1,4,"REQ-2","VIRTUAL")));sales.sync("QM",full);sales.sync("QM",full);
        assertEquals("COMPLETE",service.detail("QM","ticket",ticket()).get("progress"));assertEquals(2,service.receiptBatches("QM",ticket(),1,20).getTotal());assertEquals(1,service.page("QM","line",m("documentType","SALE_OUT","skuSn","SKU-1"),1,20).getTotal());assertEquals(0,calls.get());
    }
    @Test void sameVersionConflictAndOlderDeliveryCannotOverwriteRecordedQuantity(){Map<String,Object> b=body(2,4);sales.sync("QM",b);assertTrue(sales.sync("QM",body(1,0)));b.put("last_error","changed");assertThrows(IllegalArgumentException.class,()->sales.sync("QM",b));assertEquals(4,db.queryForObject("SELECT number_actually FROM wms_tickets_goods",Integer.class));}
    @Test void missingMovementAndChangedPlanFailAtomically(){assertThrows(IllegalArgumentException.class,()->sales.sync("QM",mismatch()));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));sales.sync("QM",body(1,0));Map<String,Object> b=body(2,4);b.put("lines",Arrays.asList(line(1,"V",9,4)));assertThrows(IllegalArgumentException.class,()->sales.sync("QM",b));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_movement",Integer.class));}
    Map<String,Object> mismatch(){Map<String,Object> b=body(1,4);b.put("movements",Collections.emptyList());return b;}
    @Test void outboundCannotUsePurchaseCancelPostingOrWmsOperations(){sales.sync("QM",body(1,0));assertThrows(IllegalArgumentException.class,()->service.cancel("QM","ticket",ticket(),"不能从这里取消","tester"));assertThrows(IllegalArgumentException.class,()->service.postInventory("QM",ticket(),"tester"));assertThrows(IllegalArgumentException.class,()->service.completeVirtual("QM",ticket(),"tester"));assertEquals(0,calls.get());}
    @Test void realWarehouseConfirmationUsesSalesMovementsAndNeverInboundReceipts(){db.update("UPDATE wms_simulation_store_info SET outbound_mode=1");Map<String,Object> b=body(1,8);b.put("dispatch_state","ACCEPTED");b.put("movements",Arrays.asList(movement(1,8,"WMS-1","WMS")));sales.sync("QM",b);Map<String,Object> detail=service.detail("QM","ticket",ticket());assertEquals("真实出库",detail.get("modeLabel"));assertEquals(8L,((Number)detail.get("numberPosted")).longValue());assertEquals("COMPLETE",detail.get("progress"));assertEquals(1,service.receiptBatches("QM",ticket(),1,20).getTotal());assertEquals(0,calls.get());}
    @Test void simultaneousInitialDeliveryCreatesOnlyOneHeaderAndOneMovement() throws Exception {Map<String,Object> b=body(1,4);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{List<Future<Boolean>> jobs=new ArrayList<>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{start.await();return sales.sync("QM",b);}));start.countDown();for(Future<Boolean> job:jobs)assertTrue(job.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_movement",Integer.class));}
    @Test void multipleVirtualStoresHaveSeparatePlansAndCannotLoseAStoreOnNextRevision(){long relation=db.queryForObject("SELECT owner_warehouse_id FROM wms_simulation_store_info WHERE wms_simulation_code='V'",Long.class);warehouses.save("QM","simulationStore",m("wmsSimulationCode","V2","wmsSimulationName","虚仓2","inboundMode",2,"outboundMode",2,"status",2,"ownerWarehouseId",relation));Map<String,Object> b=body(1,4);b.put("lines",Arrays.asList(line(1,"V",8,4),line(2,"V2",3,0)));sales.sync("QM",b);assertEquals(2,service.page("QM","ticket",m("documentType","SALE_OUT"),1,20).getTotal());assertThrows(IllegalArgumentException.class,()->sales.sync("QM",body(2,4)));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM wms_tickets_goods",Integer.class));}
    @Test void separateVirtualWarehouseShipmentsCreateIndependentSalesDocumentsAndMovements(){
        long relation=db.queryForObject("SELECT owner_warehouse_id FROM wms_simulation_store_info WHERE wms_simulation_code='V'",Long.class);
        warehouses.save("QM","simulationStore",m("wmsSimulationCode","V2","wmsSimulationName","虚仓2","inboundMode",2,"outboundMode",2,"status",2,"ownerWarehouseId",relation));
        Map<String,Object> first=body(1,0);first.put("shipment_sn","XS_V1");first.put("lines",Arrays.asList(line(1,"V",6,0)));
        Map<String,Object> second=body(1,0);second.put("id",2);second.put("shipment_sn","XS_V2");second.put("lines",Arrays.asList(line(2,"V2",4,0)));
        sales.sync("QM",first);sales.sync("QM",second);sales.sync("QM",first);sales.sync("QM",second);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_document",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));
        assertEquals(10,db.queryForObject("SELECT SUM(number_expected) FROM wms_tickets_goods",Integer.class));
        first.put("source_version",2);first.put("status","SHIPPED");first.put("lines",Arrays.asList(line(1,"V",6,6)));first.put("movements",Arrays.asList(movement(1,6,"REQ-V1","VIRTUAL")));
        sales.sync("QM",first);sales.sync("QM",first);
        long firstTicket=db.queryForObject("SELECT ticket_id FROM wms_sales_outbound_document WHERE shipment_id=1",Long.class),secondTicket=db.queryForObject("SELECT ticket_id FROM wms_sales_outbound_document WHERE shipment_id=2",Long.class);
        assertEquals("COMPLETE",service.detail("QM","ticket",firstTicket).get("progress"));
        assertEquals(0L,((Number)service.detail("QM","ticket",secondTicket).get("numberPosted")).longValue());
        assertEquals(4L,((Number)service.detail("QM","ticket",secondTicket).get("remainingQuantity")).longValue());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_movement",Integer.class));assertEquals(0,calls.get());
    }
    @Test void cancelledUnexecutedSourceAndCompanyIsolationArePreserved(){Map<String,Object> b=body(1,0);b.put("status","VOIDED");sales.sync("QM",b);assertEquals("CANCELED",service.detail("QM","ticket",ticket()).get("progress"));assertThrows(IllegalArgumentException.class,()->sales.sync("QM",body(2,0)));assertThrows(IllegalArgumentException.class,()->sales.sync("OTHER",body(1,0)));assertEquals(0,service.page("OTHER","ticket",m(),1,20).getTotal());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_tickets",Integer.class));}
    @Test void waitingCarrierDispatchingAndCancellingStagesStillHaveDocumentDetails(){long version=0;for(String status:Arrays.asList("WAIT_CARRIER","DISPATCHING","VOIDING")){Map<String,Object> b=body(++version,0);b.put("status",status);assertTrue(sales.sync("QM",b));assertEquals(1,service.detail("QM","ticket",ticket()).get("lines") instanceof List?((List<?>)service.detail("QM","ticket",ticket()).get("lines")).size():0);}assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_movement",Integer.class));}
    @Test void firstActualReceiptConfirmsModeAfterWarehouseConfigurationChanges(){
        sales.sync("QM",body(1,0));db.update("UPDATE wms_simulation_store_info SET outbound_mode=1");sales.sync("QM",body(2,0));
        assertEquals(2,db.queryForObject("SELECT actual_warehouse FROM wms_tickets",Integer.class));
        Map<String,Object> actual=body(3,8);actual.put("dispatch_state","ACCEPTED");actual.put("movements",Arrays.asList(movement(1,8,"WMS-ACTUAL","WMS")));sales.sync("QM",actual);sales.sync("QM",actual);
        assertEquals(1,db.queryForObject("SELECT actual_warehouse FROM wms_tickets",Integer.class));assertEquals(8,db.queryForObject("SELECT number_actually FROM wms_tickets_goods",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_movement",Integer.class));assertEquals("COMPLETE",service.detail("QM","ticket",ticket()).get("progress"));assertEquals(0,calls.get());
    }
    @Test void recordedActualOutboundModeCannotBeChangedByLaterReceipt(){
        sales.sync("QM",body(1,4));Map<String,Object> changed=body(2,8);changed.put("movements",Arrays.asList(movement(1,8,"NEW-WMS","WMS")));
        assertThrows(IllegalArgumentException.class,()->sales.sync("QM",changed));assertEquals(2,db.queryForObject("SELECT actual_warehouse FROM wms_tickets",Integer.class));assertEquals(4,db.queryForObject("SELECT number_actually FROM wms_tickets_goods",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wms_sales_outbound_movement",Integer.class));assertEquals(0,calls.get());
    }
    @Test @SuppressWarnings("unchecked") void stoppedRemainderClosesPartialOutboundWithoutRemovingActualMovements(){Map<String,Object> body=body(1,4);((Map<String,Object>)((List<?>)body.get("lines")).get(0)).put("cancelled_quantity",4);sales.sync("QM",body);Map<String,Object> detail=service.detail("QM","ticket",ticket());assertEquals("CLOSED",detail.get("progress"));assertEquals("已结束（剩余已截停）",detail.get("progressLabel"));assertEquals(0L,((Number)detail.get("remainingQuantity")).longValue());assertEquals(4L,((Number)detail.get("numberPosted")).longValue());assertEquals(1,service.receiptBatches("QM",ticket(),1,20).getTotal());assertEquals(0,calls.get());}
}
