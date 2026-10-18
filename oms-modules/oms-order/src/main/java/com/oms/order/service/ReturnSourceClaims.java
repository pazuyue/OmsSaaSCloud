package com.oms.order.service;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Called after locking the actual order line. Current reads fence refund/exchange claims together. */
final class ReturnSourceClaims {
    private ReturnSourceClaims(){}
    static long refundUsed(OrderRepository r,String c,long shipmentLine,long excludedPlan){long used=0;for(Map<String,Object> p:r.db().query("SELECT id,quantity,accepted_quantity,state FROM oms_refund_return_plan WHERE company_code=? AND shipment_line_id=? AND id<>? ORDER BY id FOR UPDATE",ROW,c,shipmentLine,excludedPlan))used=Math.addExact(used,"RELEASED".equals(text(p,"state"))?number(p,"accepted_quantity"):number(p,"quantity"));return used;}
    static long exchangeUsed(OrderRepository r,String c,long shipmentLine){long used=0;for(Map<String,Object> e:r.db().query("SELECT requested_quantity FROM oms_exchange WHERE company_code=? AND source_shipment_line_id=? AND approved_version IS NOT NULL AND state NOT IN ('REJECTED','CANCELED') ORDER BY id FOR UPDATE",ROW,c,shipmentLine))used=Math.addExact(used,number(e,"requested_quantity"));return used;}
}
