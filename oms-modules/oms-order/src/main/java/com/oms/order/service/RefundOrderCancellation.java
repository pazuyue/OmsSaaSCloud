package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Local parent-state convergence after a confirmed cancellation receipt.
 * Caller holds the original shop/platform/order locks. Never releases stock or changes refund money.
 */
final class RefundOrderCancellation {
    private RefundOrderCancellation() {}
    static void reconcile(OrderRepository r,String company,long id,String refundSn,String actor) {
        Map<String,Object> order=r.db().queryForMap("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",company,id);
        if(!"WAIT_ALLOCATION".equals(text(order,"status")))return;
        Map<String,Object> totals=OrderFulfillmentLines.balance(r,company,id),shipments=totals;
        long quantity=number(totals,"quantity"),cancelled=number(totals,"cancelled"),reserved=number(totals,"reserved"),shipped=number(shipments,"shipped"),required=quantity-cancelled;
        require(quantity>0&&cancelled>0&&required>=shipped&&reserved>=shipped,"退款取消后的原订单数量不一致，请核对原回执");
        if(required==shipped) {
            require(reserved==shipped&&number(shipments,"pending")==0&&number(order,"allocation_quantity")<=shipped,"原订单仍有未确认释放的占用或发货安排，不能结束履约");
            boolean voided=shipped==0;String reason="退款 "+refundSn+" 已确认取消全部剩余未发商品，原订单结束履约";
            r.db().update("UPDATE oms_order SET status=?,inventory_status=?,allocation_status=?,stock_attempt_status='DONE',cutoff_state=IF(cutoff_id IS NULL,cutoff_state,'COMPLETED'),control_version=control_version+1,void_at=IF(?=1,NOW(),void_at),void_reason=IF(?=1,?,void_reason),void_by=IF(?=1,?,void_by) WHERE company_code=? AND id=?",voided?"VOIDED":"CLOSED",voided?"RELEASED":"RESERVED",voided?"NONE":text(order,"allocation_status"),voided?1:0,voided?1:0,reason,voided?1:0,actor,company,id);
            r.log(company,number(order,"platform_order_id"),"REFUND_ORDER_COMPLETE","SUCCESS",reason+"；原出库、审核记录及库存回执保留",actor);
        } else {
            String reason="退款取消后的剩余未发商品待核对";
            String inventory=reserved==required?"RESERVED":reserved>0?"PART_RESERVED":"WAIT_RESERVE";
            r.db().update("UPDATE oms_order SET review_status='PENDING',review_decision='MANUAL_REQUIRED',review_by='',review_at=NULL,review_reason=?,inventory_status=?,control_version=control_version+1 WHERE company_code=? AND id=?",reason,inventory,company,id);
        }
    }
}
