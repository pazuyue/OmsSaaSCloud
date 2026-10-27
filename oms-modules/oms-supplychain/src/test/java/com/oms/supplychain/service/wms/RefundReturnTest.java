package com.oms.supplychain.service.wms;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.oms.common.model.dto.wms.WmsInventoryBatchDto;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RefundReturnTest {
    ExchangeReturnTest f;RefundReturnService service;Map<String,Object> task;
    @BeforeEach void setup(){f=new ExchangeReturnTest();f.setup();for(String t:Arrays.asList("wms_refund_parcel_receipt","wms_refund_parcel_member","wms_refund_parcel","wms_refund_return_review","wms_refund_return_line","wms_refund_return_event","wms_refund_return"))f.jdbc.update("DELETE FROM "+t);f.jdbc.update("INSERT INTO wms_refund_return(company_code,plan_id,return_sn,refund_sn,refund_id,order_sn,platform_tid,original_shipment_sn,store_code,sku_sn,quantity,unit_cost,connection_id,external_warehouse,external_owner,real_store_code,owner_code,payload_hash) VALUES ('QM',1,'THK_TEST','TK_TEST',20,'SO_TEST','123456','XS_TEST','V1','A',5,2.50,1,'EXT-W','EXT-O','W','O','hash')");task=f.db.one("SELECT * FROM wms_refund_return WHERE plan_id=1");service=new RefundReturnService();ReflectionTestUtils.setField(service,"carrierBridge",com.oms.supplychain.CarrierFixture.bridge());ReflectionTestUtils.setField(service,"db",f.db);ReflectionTestUtils.setField(service,"store",f.db);ReflectionTestUtils.setField(service,"inventory",f.inventory);ReflectionTestUtils.setField(service,"logs",mock(WmsInteractionLog.class));}
    WmsProtocol.Receipt receipt(String id,int qty,boolean complete){WmsProtocol.Receipt r=f.receipt(id,qty,complete);r.ticketSn="THK_TEST";return r;}
    void post(){ReflectionTestUtils.invokeMethod(service,"post","QM",1L);}
    @Test void validReceiptPostsOnceUnderRefundNamespaceAtOriginalCost(){service.accept("QM",task,receipt("M",5,true),0);post();post();ArgumentCaptor<WmsInventoryBatchDto> c=ArgumentCaptor.forClass(WmsInventoryBatchDto.class);verify(f.inventory).addInventory(c.capture(),eq("QM"));assertTrue(c.getValue().getRelationSn().startsWith("REFUND_RETURN_"));assertEquals(0,new java.math.BigDecimal("2.50").compareTo(c.getValue().getWmsInventoryBatch().getTransactionPrice()));assertEquals(0L,f.jdbc.queryForObject("SELECT accepted_quantity FROM wms_exchange_return",Long.class));}
    @Test void wrongSkuAndFinalShortReceiptBecomeDurableReviewWithoutStock(){WmsProtocol.Receipt wrong=receipt("WRONG",2,false);wrong.lines.get(0).sku="B";service.accept("QM",task,wrong,0);service.accept("QM",task,wrong,0);service.accept("QM",task,receipt("SHORT",4,true),0);post();assertEquals(2,id(service.detail("QM",1).get("pending_review_count")));assertEquals(0,id(service.detail("QM",1).get("accepted_quantity")));verifyNoInteractions(f.inventory);}
    @Test void excessCannotBeAcceptedEvenByManualReview(){service.accept("QM",task,receipt("EXCESS",6,true),0);long review=f.jdbc.queryForObject("SELECT id FROM wms_refund_return_review",Long.class);ReflectionTestUtils.invokeMethod(service,"confirmCancel","QM",id(task.get("id")));assertThrows(IllegalArgumentException.class,()->service.documentAction("QM",1,id(task.get("id")),"review",map("review_id",review,"decision","ACCEPT","actor","test","reason","review","evidence","proof")));verifyNoInteractions(f.inventory);}
    @Test void canceledReceiptWaitsForReviewThenPostsOnce(){ReflectionTestUtils.invokeMethod(service,"confirmCancel","QM",id(task.get("id")));WmsProtocol.Receipt r=receipt("LATE",2,false);service.accept("QM",task,r,0);post();verifyNoInteractions(f.inventory);long review=f.jdbc.queryForObject("SELECT id FROM wms_refund_return_review",Long.class);service.documentAction("QM",1,id(task.get("id")),"review",map("review_id",review,"decision","ACCEPT","actor","test","reason","verified","evidence","proof"));service.accept("QM",task,r,0);post();verify(f.inventory,times(1)).addInventory(any(),eq("QM"));assertEquals(2,id(service.detail("QM",1).get("posted_quantity")));}
    @Test void refundProtocolUsesReturnTypeAndRefundReference(){Map<String,Object> t=new LinkedHashMap<>(task);t.put("business_type","REFUND_IN");t.put("business_sn","TK_TEST");String body=f.protocol.request(f.connection(),"fixture","CREATE",t).body;assertTrue(body.contains("<orderType>THRK</orderType>"));assertTrue(body.contains("TK_TEST"));assertTrue(QimenReturnProtocol.refundDocument(f.xml().replace("THX_TEST","THK_TEST")));}
    @Test void otherCompanyCannotReadOrPostRefundReturn(){assertThrows(IllegalArgumentException.class,()->service.detail("OTHER",1));WmsProtocol.Receipt r=receipt("M",5,true);r.warehouse="OTHER";assertThrows(IllegalArgumentException.class,()->service.accept("QM",task,r,0));verifyNoInteractions(f.inventory);}
    @Test void progressAcknowledgementCannotLoseANewerWarehouseReceipt(){f.jdbc.update("UPDATE wms_refund_return SET progress_revision=2,notify_pending=1 WHERE id=?",task.get("id"));service.acknowledge("QM",1,map("id",task.get("id"),"progress_revision",1));assertEquals(1,service.pending("QM").size());service.acknowledge("QM",1,map("id",task.get("id"),"progress_revision",2));assertEquals(0,service.pending("QM").size());assertEquals(0,service.pending("OTHER").size());}
    @Test void lateReviewCannotBeAcceptedUntilReplacementIsStoppedButDoesNotPreventStoppingIt(){
        ReflectionTestUtils.invokeMethod(service,"confirmCancel","QM",id(task.get("id")));
        service.accept("QM",task,receipt("LATE",1,false),0);
        long review=f.jdbc.queryForObject("SELECT id FROM wms_refund_return_review",Long.class);
        long next=f.db.insert("wms_refund_return",map("company_code","QM","plan_id",1,"generation",2,"return_sn","THK_NEXT","refund_sn","TK_TEST","refund_id",20,"order_sn","SO_TEST","platform_tid","123456","original_shipment_sn","XS_TEST","store_code","V1","sku_sn","A","quantity",5,"request_quantity",5,"unit_cost","2.50","connection_id",1,"external_warehouse","EXT-W","external_owner","EXT-O","real_store_code","W","owner_code","O","payload_hash","next","dispatch_state","ACCEPTED","external_order","WH_NEXT","attempts",1));
        Map<String,Object> accept=map("review_id",review,"decision","ACCEPT","actor","test","reason","verified","evidence","proof");
        assertThrows(IllegalArgumentException.class,()->service.documentAction("QM",1,id(task.get("id")),"review",accept));
        assertThrows(IllegalArgumentException.class,()->service.documentAction("QM",1,next,"dispatch",Collections.emptyMap()));
        // A mock failed connection leaves a durable cancellation intent; it must not fail at noPending.
        WmsConnections connection=mock(WmsConnections.class);when(connection.get(anyString(),anyLong())).thenThrow(new IllegalArgumentException("fixture unavailable"));ReflectionTestUtils.setField(service,"connections",connection);
        service.documentAction("QM",1,next,"cancel",map("document_version",1,"mode","WITHDRAW","reason","stop replacement","actor","test"));
        assertEquals("REJECTED",f.jdbc.queryForObject("SELECT cancel_state FROM wms_refund_return WHERE id=?",String.class,next));
        assertEquals("WAIT_REVIEW",f.jdbc.queryForObject("SELECT state FROM wms_refund_return_review WHERE id=?",String.class,review));verifyNoInteractions(f.inventory);
        ReflectionTestUtils.invokeMethod(service,"confirmCancel","QM",next);
        service.documentAction("QM",1,id(task.get("id")),"review",accept);
        assertEquals(1,id(service.detail("QM",1).get("posted_quantity")));post();verify(f.inventory,times(1)).addInventory(any(),eq("QM"));
    }
    @Test void rejectReceiptDoesNotAddStock(){
        ReflectionTestUtils.invokeMethod(service,"confirmCancel","QM",id(task.get("id")));service.accept("QM",task,receipt("LATE",1,false),0);
        long review=f.jdbc.queryForObject("SELECT id FROM wms_refund_return_review",Long.class);
        service.documentAction("QM",1,id(task.get("id")),"review",map("review_id",review,"decision","REJECT","actor","test","reason","warehouse confirmed duplicate notice","evidence","physical goods remain under replacement"));
        assertEquals("REJECTED",f.jdbc.queryForObject("SELECT state FROM wms_refund_return_review WHERE id=?",String.class,review));assertEquals(0,id(service.detail("QM",1).get("posted_quantity")));verifyNoInteractions(f.inventory);
    }
}
