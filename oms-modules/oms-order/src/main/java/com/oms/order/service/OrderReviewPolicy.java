package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Shared review/hold/release gates; stock progress never changes the review result. */
public final class OrderReviewPolicy {
    private OrderReviewPolicy(){}
    /** Public business stage; persisted status remains the fulfillment lifecycle guard. */
    public static String businessStatus(Map<String,Object> row){
        String status=text(row,"status");
        if(!"WAIT_ALLOCATION".equals(status))return status;
        if(number(row,"demand_quantity")>0&&number(row,"shipped_quantity")>=number(row,"demand_quantity")-number(row,"cancelled_quantity"))return "SHIPPED";
        if(number(row,"shipped_quantity")>0)return "PART_SHIPPED";
        if(!"APPROVED".equals(text(row,"review_status")))return "WAIT_REVIEW";
        if("FULL".equals(text(row,"allocation_status"))){
            if(number(row,"pending_carrier_count")>0||number(row,"shipment_quantity")<number(row,"allocation_quantity"))return "WAIT_CARRIER";
            if(number(row,"dispatching_count")>0)return "DISPATCHING";
            if(number(row,"warehouse_accepted_quantity")>=number(row,"allocation_quantity")&&number(row,"allocation_quantity")>0)return "WAREHOUSE_PROCESSING";
            return number(row,"warehouse_accepted_quantity")>0?"PART_DISPATCHED":"WAIT_DISPATCH";
        }
        return number(row,"allocation_quantity")>0?"PART_ALLOCATED":status;
    }
    public static String businessStatusSql(){
        String waiting="EXISTS(SELECT 1 FROM oms_order_shipment cs WHERE cs.company_code=o.company_code AND cs.order_id=o.id AND cs.business_type='SALE' AND cs.status='WAIT_CARRIER') OR (SELECT COALESCE(SUM(cs.quantity),0) FROM oms_order_shipment cs WHERE cs.company_code=o.company_code AND cs.order_id=o.id AND cs.business_type='SALE' AND cs.status<>'VOIDED')<o.allocation_quantity";
        String sending="EXISTS(SELECT 1 FROM oms_order_shipment cs WHERE cs.company_code=o.company_code AND cs.order_id=o.id AND cs.business_type='SALE' AND cs.status='DISPATCHING')";
        String accepted="(SELECT COALESCE(SUM(cs.quantity),0) FROM oms_order_shipment cs WHERE cs.company_code=o.company_code AND cs.order_id=o.id AND cs.business_type='SALE' AND cs.status IN ('WAREHOUSE_PROCESSING','PART_SHIPPED','SHIPPED'))";
        String shipped="(SELECT COALESCE(SUM(sl.shipped_quantity),0) FROM oms_order_shipment_line sl JOIN oms_order_shipment ss ON ss.company_code=sl.company_code AND ss.id=sl.shipment_id WHERE ss.company_code=o.company_code AND ss.order_id=o.id AND ss.business_type='SALE')";
        String demand="(SELECT COALESCE(SUM(ol.quantity-ol.cancelled_quantity),0) FROM oms_order_line ol WHERE ol.company_code=o.company_code AND ol.order_id=o.id AND ol.business_type='SALE')";
        return "CASE WHEN o.status<>'WAIT_ALLOCATION' THEN o.status WHEN "+shipped+">= "+demand+" AND "+demand+">0 THEN 'SHIPPED' WHEN "+shipped+">0 THEN 'PART_SHIPPED' WHEN COALESCE(o.review_status,'')<>'APPROVED' THEN 'WAIT_REVIEW' WHEN o.allocation_status='FULL' THEN CASE WHEN "+waiting+" THEN 'WAIT_CARRIER' WHEN "+sending+" THEN 'DISPATCHING' WHEN "+accepted+">=o.allocation_quantity AND o.allocation_quantity>0 THEN 'WAREHOUSE_PROCESSING' WHEN "+accepted+">0 THEN 'PART_DISPATCHED' ELSE 'WAIT_DISPATCH' END WHEN o.allocation_quantity>0 THEN 'PART_ALLOCATED' ELSE o.status END";
    }
    public static String systemBlock(Map<String,Object> row){
        if(!text(row,"gift_block_reason").isEmpty())return text(row,"gift_block_reason");
        if(!text(row,"refund_block_reason").isEmpty())return text(row,"refund_block_reason");
        if(!text(row,"exchange_block_reason").isEmpty())return text(row,"exchange_block_reason");
        if(number(row,"change_pending")!=0)return "平台关键交易信息已变化，等待订单更新处理";
        if(!text(row,"fulfillment_block_reason").isEmpty())return text(row,"fulfillment_block_reason");
        if(!"WAIT_SELLER_SEND_GOODS".equals(text(row,"platform_status")))return "平台交易状态不允许履约";
        return "";
    }
    public static List<String> releaseBlocks(Map<String,Object> row){
        List<String> reasons=new ArrayList<>();if("CUTTING".equals(text(row,"status"))){reasons.add("截单处理中，已停止新增履约");return reasons;}if(Arrays.asList("VOIDING","VOIDED").contains(text(row,"status"))){reasons.add("VOIDED".equals(text(row,"status"))?"订单已作废":"订单作废处理中");return reasons;}String system=systemBlock(row);if(!system.isEmpty())reasons.add(system);
        if(number(row,"manual_hold")==1)reasons.add("人工挂起："+text(row,"hold_reason"));
        if(!"APPROVED".equals(text(row,"review_status")))reasons.add("尚未审核通过");
        if(!"RESERVED".equals(text(row,"inventory_status"))&&!(number(row,"allocation_allow_partial")==1&&number(row,"reserved_quantity")>number(row,"allocation_quantity")))reasons.add("逻辑预占尚未全部完成");
        if("FULL".equals(text(row,"allocation_status")))reasons.add("订单已全部分仓，请到发货单处理后续履约");
        if(!"WAIT_ALLOCATION".equals(text(row,"status")))reasons.add("业务订单当前阶段不允许进入分仓");
        return reasons;
    }
    public static void requireRelease(Map<String,Object> row){List<String> reasons=releaseBlocks(row);require(reasons.isEmpty(),String.join("；",reasons));}
    public static void decorate(Map<String,Object> row){
        row.put("business_status",businessStatus(row));
        String block=systemBlock(row);boolean manual=number(row,"manual_hold")==1;
        row.put("system_hold_reason",block);row.put("processing_status",Arrays.asList("VOIDING","VOIDED").contains(text(row,"status"))?text(row,"status"):manual||!block.isEmpty()?"HELD":"NORMAL");
        row.put("can_review","PENDING".equals(text(row,"review_status"))&&block.isEmpty()&&"WAIT_ALLOCATION".equals(text(row,"status")));
        row.put("can_hold",!manual&&"WAIT_ALLOCATION".equals(text(row,"status")));
        row.put("can_resume",manual&&block.isEmpty()&&"WAIT_ALLOCATION".equals(text(row,"status")));
        List<String> reasons=releaseBlocks(row);row.put("release_eligible",reasons.isEmpty());row.put("release_blocks",reasons);
        row.put("can_allocate",reasons.isEmpty()&&number(row,"allocation_enabled")==1);
    }
}
