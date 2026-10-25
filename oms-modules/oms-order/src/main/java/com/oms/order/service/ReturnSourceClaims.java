package com.oms.order.service;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Called after locking the actual order line. Current reads fence refund/exchange claims together. */
final class ReturnSourceClaims {
    private ReturnSourceClaims(){}
    static long refundUsed(OrderRepository r,String c,long shipmentLine,long excludedPlan){return refundUsed(r,c,shipmentLine,excludedPlan,0);}
    static long refundUsed(OrderRepository r,String c,long shipmentLine,long excludedPlan,long ownClaimRefund){long used=0;for(Map<String,Object> p:r.db().query("SELECT id,quantity,accepted_quantity,state FROM oms_refund_return_plan WHERE company_code=? AND shipment_line_id=? AND id<>? ORDER BY id FOR UPDATE",ROW,c,shipmentLine,excludedPlan))used=Math.addExact(used,"RELEASED".equals(text(p,"state"))?number(p,"accepted_quantity"):number(p,"quantity"));for(Map<String,Object> claim:r.db().query("SELECT c.quantity FROM oms_refund_bundle_claim c WHERE c.company_code=? AND c.shipment_line_id=? AND c.refund_id<>? AND NOT EXISTS(SELECT 1 FROM oms_refund_return_plan p WHERE p.company_code=c.company_code AND p.refund_id=c.refund_id AND p.shipment_line_id=c.shipment_line_id) ORDER BY c.refund_id FOR UPDATE",ROW,c,shipmentLine,ownClaimRefund))used=Math.addExact(used,number(claim,"quantity"));for(Map<String,Object> task:r.db().query("SELECT quantity FROM oms_refund_goods_task WHERE company_code=? AND shipment_line_id=? AND refund_id<>? AND action IN ('RETURN','INTERCEPT','REFUSAL') AND state<>'CANCELED' AND return_plan_id IS NULL ORDER BY id FOR UPDATE",ROW,c,shipmentLine,ownClaimRefund))used=Math.addExact(used,number(task,"quantity"));return used;}
    static long exchangeUsed(OrderRepository r,String c,long shipmentLine){return exchangeUsed(r,c,shipmentLine,0);}
    static long exchangeUsed(OrderRepository r,String c,long shipmentLine,long excluded){long used=0;for(Map<String,Object> e:r.db().query("SELECT requested_quantity FROM oms_exchange WHERE company_code=? AND source_shipment_line_id=? AND id<>? AND approved_version IS NOT NULL AND state NOT IN ('REJECTED','CANCELED') ORDER BY id FOR UPDATE",ROW,c,shipmentLine,excluded))used=Math.addExact(used,number(e,"requested_quantity"));return used;}
}
