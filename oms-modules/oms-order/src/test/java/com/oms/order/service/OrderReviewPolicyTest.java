package com.oms.order.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderReviewPolicyTest {
    private Map<String,Object> order(String review,String inventory){
        Map<String,Object> row=new HashMap<>();
        row.put("status","WAIT_ALLOCATION");row.put("review_status",review);
        row.put("inventory_status",inventory);row.put("platform_status","WAIT_SELLER_SEND_GOODS");
        return row;
    }

    @Test void pendingReviewDoesNotLookReadyForAllocationEvenWithReservedStock(){
        Map<String,Object> row=order("PENDING","RESERVED");OrderReviewPolicy.decorate(row);
        assertEquals("WAIT_REVIEW",row.get("business_status"));
        assertEquals("WAIT_ALLOCATION",row.get("status"));
        assertEquals(true,row.get("can_review"));assertEquals(false,row.get("release_eligible"));
        assertEquals(Collections.singletonList("尚未审核通过"),row.get("release_blocks"));
        assertThrows(IllegalArgumentException.class,()->OrderReviewPolicy.requireRelease(row));
    }

    @Test void approvalMovesBusinessStageWithoutClaimingStockIsReady(){
        Map<String,Object> row=order("PENDING","PART_RESERVED");OrderReviewPolicy.decorate(row);
        assertEquals("WAIT_REVIEW",row.get("business_status"));
        row.put("review_status","APPROVED");OrderReviewPolicy.decorate(row);
        assertEquals("WAIT_ALLOCATION",row.get("business_status"));
        assertEquals("PART_RESERVED",row.get("inventory_status"));
        assertEquals(false,row.get("release_eligible"));
        assertEquals(Collections.singletonList("逻辑预占尚未全部完成"),row.get("release_blocks"));
        row.put("inventory_status","RESERVED");OrderReviewPolicy.decorate(row);
        assertEquals(true,row.get("release_eligible"));
        assertDoesNotThrow(()->OrderReviewPolicy.requireRelease(row));
    }

    @Test void manualAndPlatformBlocksRemainIndependentOfBusinessStage(){
        Map<String,Object> row=order("APPROVED","RESERVED");
        row.put("manual_hold",1);row.put("hold_reason","核对地址");row.put("change_pending",1);
        OrderReviewPolicy.decorate(row);
        assertEquals("WAIT_ALLOCATION",row.get("business_status"));assertEquals("HELD",row.get("processing_status"));
        assertEquals(Arrays.asList("平台关键交易信息已变化，等待订单更新处理","人工挂起：核对地址"),row.get("release_blocks"));
        assertEquals(false,row.get("release_eligible"));
    }

    @Test void voidStagesTakePrecedenceOverPendingReviewAndStock(){
        for(String status:Arrays.asList("VOIDING","VOIDED")){
            Map<String,Object> row=order("PENDING","WAIT_RESERVE");row.put("status",status);
            OrderReviewPolicy.decorate(row);
            assertEquals(status,row.get("business_status"));assertEquals(status,row.get("status"));
            assertEquals(false,row.get("can_review"));assertEquals(false,row.get("release_eligible"));
        }
    }

    @Test void missingReviewDoesNotImplyApproval(){
        Map<String,Object> row=order(null,"RESERVED");OrderReviewPolicy.decorate(row);
        assertEquals("WAIT_REVIEW",row.get("business_status"));assertEquals(false,row.get("release_eligible"));
    }

    @Test void zeroEffectiveDemandCannotBecomeShippedOrExposeReviewAndAllocation(){
        Map<String,Object> row=order("PENDING","RESERVED");row.put("demand_quantity",1);row.put("cancelled_quantity",1);row.put("shipped_quantity",0);
        OrderReviewPolicy.decorate(row);
        assertNotEquals("SHIPPED",row.get("business_status"));assertEquals(false,row.get("review_required"));
        for(String key:Arrays.asList("can_review","can_hold","can_resume","can_allocate","release_eligible"))assertEquals(false,row.get(key),key);
        assertEquals(Collections.singletonList("订单没有剩余可履约商品"),row.get("release_blocks"));
    }

    @Test void alreadyShippedAndCanceledRemainderNeverReenterReview(){
        Map<String,Object> row=order("PENDING","RESERVED");row.put("demand_quantity",3);row.put("cancelled_quantity",2);row.put("shipped_quantity",1);
        OrderReviewPolicy.decorate(row);assertEquals("SHIPPED",row.get("business_status"));assertEquals(false,row.get("can_review"));assertEquals(false,row.get("review_required"));
        row.put("cancelled_quantity",1);OrderReviewPolicy.decorate(row);assertEquals("PART_SHIPPED",row.get("business_status"));assertEquals(true,row.get("can_review"));
    }
}
