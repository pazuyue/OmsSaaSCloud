package com.oms.order.service;

import java.math.BigDecimal;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Original-suborder money budget. Call under lockOriginal before reserving approval or issuing payment. */
final class RefundMoneyScope {
    final BigDecimal paid, used, remaining;
    final boolean priorMoneySettled;
    private RefundMoneyScope(BigDecimal paid,BigDecimal used,boolean settled){this.paid=paid;this.used=used;this.remaining=paid==null?null:paid.subtract(used).max(BigDecimal.ZERO);this.priorMoneySettled=settled;}
    static RefundMoneyScope inspect(OrderRepository r,String c,Map<String,Object> b){
        Map<String,Object> doc=OrderDocumentInfo.stored(r.db().queryForObject("SELECT document_info FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",String.class,c,b.get("order_id")));
        BigDecimal paid=null;Object raw=doc.get("lines");
        if(raw instanceof Collection)for(Object item:(Collection<?>)raw){Map<String,Object> l=obj(item);if(text(b,"platform_oid").equals(text(l,"platform_oid"))&&l.get("goods_payment")!=null&&text(l,"payment_source").startsWith("平台"))paid=money(l.get("goods_payment"));}
        // Successful facts and adopted pending refunds reserve money once per platform refund.
        // A normal aggregate may reuse a REPEATABLE READ snapshot created before lockOriginal.
        // Locking reads see approvals committed while this transaction waited for the original order.
        List<Map<String,Object>> rows=r.db().query("SELECT CASE WHEN y.funds_state='SUCCESS' THEN y.refund_fee WHEN x.platform_status='SUCCESS' THEN x.refund_fee WHEN y.review_state='APPROVED' AND x.platform_status<>'CLOSED' THEN y.refund_fee ELSE 0 END used,"+
            "CASE WHEN x.platform_status='CLOSED' AND COALESCE(y.funds_state,'')<>'SUCCESS' THEN 0 WHEN x.source_state='VALID' AND x.platform_status='SUCCESS' AND y.review_state='APPROVED' AND y.goods_action='KEEP' THEN 0 ELSE 1 END unsettled "+
            "FROM oms_platform_refund x LEFT JOIN oms_refund y ON y.company_code=x.company_code AND y.platform_refund_id=x.id WHERE x.company_code=? AND x.channel_id=? AND x.platform_tid=? AND x.platform_oid=? AND x.id<>? ORDER BY x.id FOR UPDATE",ROW,c,b.get("channel_id"),b.get("platform_tid"),b.get("platform_oid"),b.get("platform_refund_id"));
        BigDecimal used=BigDecimal.ZERO;boolean settled=true;for(Map<String,Object> row:rows){used=used.add(money(row.get("used")));settled&=number(row,"unsettled")==0;}
        return new RefundMoneyScope(paid,used,settled);
    }
    void requireAvailable(Object fee,boolean strict){
        if(paid==null){require(!strict,"原子单平台商品实付不明确，请人工核对累计可退金额");return;}
        require(money(fee).compareTo(remaining)<=0,"本子单原实付 "+paid+" 元，其他已退或已审核占用 "+used+" 元，剩余可退 "+remaining+" 元，本次申请超过剩余金额");
    }
}
