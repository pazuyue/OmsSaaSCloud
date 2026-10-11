package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Shared review/hold/release gates; stock progress never changes the review result. */
public final class OrderReviewPolicy {
    private OrderReviewPolicy(){}
    public static String systemBlock(Map<String,Object> row){
        if(number(row,"change_pending")!=0)return "平台关键交易信息已变化，等待订单更新处理";
        if(!text(row,"fulfillment_block_reason").isEmpty())return text(row,"fulfillment_block_reason");
        if(!"WAIT_SELLER_SEND_GOODS".equals(text(row,"platform_status")))return "平台交易状态不允许履约";
        return "";
    }
    public static List<String> releaseBlocks(Map<String,Object> row){
        List<String> reasons=new ArrayList<>();if(Arrays.asList("VOIDING","VOIDED").contains(text(row,"status"))){reasons.add("VOIDED".equals(text(row,"status"))?"订单已作废":"订单作废处理中");return reasons;}String system=systemBlock(row);if(!system.isEmpty())reasons.add(system);
        if(number(row,"manual_hold")==1)reasons.add("人工挂起："+text(row,"hold_reason"));
        if(!"APPROVED".equals(text(row,"review_status")))reasons.add("尚未审核通过");
        if(!"RESERVED".equals(text(row,"inventory_status")))reasons.add("库存尚未全部预占");
        if(!"WAIT_ALLOCATION".equals(text(row,"status")))reasons.add("业务订单当前阶段不允许进入分仓");
        return reasons;
    }
    public static void requireRelease(Map<String,Object> row){List<String> reasons=releaseBlocks(row);require(reasons.isEmpty(),String.join("；",reasons));}
    public static void decorate(Map<String,Object> row){
        String block=systemBlock(row);boolean manual=number(row,"manual_hold")==1;
        row.put("system_hold_reason",block);row.put("processing_status",Arrays.asList("VOIDING","VOIDED").contains(text(row,"status"))?text(row,"status"):manual||!block.isEmpty()?"HELD":"NORMAL");
        row.put("can_review","PENDING".equals(text(row,"review_status"))&&block.isEmpty()&&"WAIT_ALLOCATION".equals(text(row,"status")));
        row.put("can_hold",!manual&&"WAIT_ALLOCATION".equals(text(row,"status")));
        row.put("can_resume",manual&&block.isEmpty()&&"WAIT_ALLOCATION".equals(text(row,"status")));
        List<String> reasons=releaseBlocks(row);row.put("release_eligible",reasons.isEmpty());row.put("release_blocks",reasons);
    }
}
