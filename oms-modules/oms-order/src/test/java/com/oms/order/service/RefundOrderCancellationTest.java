package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static org.junit.jupiter.api.Assertions.*;

class RefundOrderCancellationTest {
    RefundServiceTest f=new RefundServiceTest();
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){f.reset();}
    @AfterEach void clear(){f.clear();}
    Map<String,Object> order(){return f.db.queryForMap("SELECT * FROM oms_order WHERE id=?",f.order);}
    void reconcile(){f.refunds.tx().execute(tx->{f.refunds.lockOriginal("VERIFY",1,"987654321012345678");RefundOrderCancellation.reconcile(f.repo,"VERIFY",f.order,"TK_TEST","退款分项截单");return null;});}
    @Test void allCanceledClosesParentAndPreservesActualApprovalHistoryWithoutReleasingStock(){
        f.db.update("UPDATE oms_order SET review_by='original',review_decision='HUMAN_APPROVED',review_at=NOW()");
        f.db.update("UPDATE oms_order_line SET cancelled_quantity=quantity,reserved_quantity=0");
        reconcile();Map<String,Object> o=order();assertEquals("VOIDED",o.get("status"));assertEquals("RELEASED",o.get("inventory_status"));assertEquals("NONE",o.get("allocation_status"));
        assertEquals("APPROVED",o.get("review_status"));assertEquals("original",o.get("review_by"));assertNotNull(o.get("review_at"));
        assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM oms_order_process_log WHERE action='REFUND_ORDER_COMPLETE'",Integer.class));
        long version=number(o,"control_version");reconcile();assertEquals(version,number(order(),"control_version"));
    }
    @Test void partialCancellationLeavesRemainingDemandForReviewAndClearsStaleApprover(){
        f.db.update("UPDATE oms_order SET review_by='old',review_decision='HUMAN_APPROVED',review_at=NOW()");
        f.db.update("UPDATE oms_order_line SET cancelled_quantity=2,reserved_quantity=3");reconcile();Map<String,Object> o=order();
        assertEquals("WAIT_ALLOCATION",o.get("status"));assertEquals("PENDING",o.get("review_status"));assertEquals("MANUAL_REQUIRED",o.get("review_decision"));assertEquals("",o.get("review_by"));assertNull(o.get("review_at"));assertEquals("RESERVED",o.get("inventory_status"));
    }
    @Test void shippedGoodsAndCanceledRemainderCloseWithoutVoidingOutboundHistory(){
        f.shipped();f.db.update("UPDATE oms_order_shipment_line SET shipped_quantity=2,cancelled_quantity=3");f.db.update("UPDATE oms_order_shipment SET status='PART_SHIPPED'");
        f.db.update("UPDATE oms_order_line SET cancelled_quantity=3,reserved_quantity=2");reconcile();
        assertEquals("CLOSED",order().get("status"));assertNull(order().get("void_at"));assertEquals(2,f.db.queryForObject("SELECT shipped_quantity FROM oms_order_shipment_line",Integer.class));
    }
    @Test void outstandingOccupationCannotBeHiddenByParentClosure(){
        f.db.update("UPDATE oms_order_line SET cancelled_quantity=quantity,reserved_quantity=0");f.db.update("UPDATE oms_order SET allocation_quantity=1");
        assertThrows(IllegalArgumentException.class,this::reconcile);assertEquals("WAIT_ALLOCATION",order().get("status"));
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_order_process_log WHERE action='REFUND_ORDER_COMPLETE'",Integer.class));
    }
    @Test void unshippedGiftOrReplacementDemandPreventsPrematureClosure(){
        f.db.update("UPDATE oms_order_line SET cancelled_quantity=quantity,reserved_quantity=0");
        f.db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,reserved_quantity,payment,gift_plan_id,stock_reference) VALUES ('VERIFY',?,'','G','gift',1,1,0,1,'GIFT:1:1')",f.order);
        reconcile();assertEquals("WAIT_ALLOCATION",order().get("status"));assertEquals("PENDING",order().get("review_status"));
    }
}
