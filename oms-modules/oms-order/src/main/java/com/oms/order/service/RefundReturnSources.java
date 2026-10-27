package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Financial identity stays on the paid suborder; physical returns follow actual shipment lineage. */
final class RefundReturnSources {
    private RefundReturnSources() {}
    static String completedExchangeLine(String line){return "("+line+".business_type='EXCHANGE' AND "+line+".exchange_id IS NOT NULL AND "+line+".bundle_id IS NULL AND EXISTS(SELECT 1 FROM oms_exchange rx WHERE rx.company_code="+line+".company_code AND rx.id="+line+".exchange_id AND rx.order_id="+line+".order_id AND rx.platform_oid="+line+".platform_oid AND rx.state='COMPLETED' AND (rx.source_kind='INTERNAL' OR rx.platform_status='5')))";}
    /** At most 200 local sources and one batched claim read; authoritative capacity is rechecked under locks at adoption. */
    static List<Map<String,Object>> available(OrderRepository r,String c,Map<String,Object> b,long quantity){
        List<Map<String,Object>> sources=r.db().query("SELECT sl.id,sl.order_line_id,sl.sku_sn,sl.shipped_quantity,ol.bundle_id FROM oms_order_shipment s JOIN oms_order_shipment_line sl ON sl.company_code=s.company_code AND sl.shipment_id=s.id JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE s.company_code=? AND s.order_id=? AND ol.platform_oid=? AND ol.gift_plan_id IS NULL AND sl.shipped_quantity>0 AND ((ol.business_type='SALE' AND ol.exchange_id IS NULL AND s.business_type='SALE' AND s.exchange_id IS NULL) OR ("+completedExchangeLine("ol")+" AND s.business_type='EXCHANGE' AND s.exchange_id=ol.exchange_id AND s.fulfillment_round=ol.fulfillment_round)) ORDER BY sl.id LIMIT 201",ROW,c,b.get("order_id"),b.get("platform_oid"));
        require(sources.size()<=200,"本子单实际发货来源超过200项，请人工核对");
        applyClaims(r,c,sources,"id",number(b,"id"),0);
        List<Map<String,Object>> eligible=new ArrayList<>();for(Map<String,Object> s:sources)if(number(s,"claimable_quantity")>=quantity)eligible.add(s);return eligible;
    }
    static void applyClaims(OrderRepository r,String c,List<Map<String,Object>> sources,String idKey,long ownRefund,long excludedExchange){
        if(sources.isEmpty())return;
        String marks=String.join(",",Collections.nCopies(sources.size(),"?"));List<Object> args=new ArrayList<>();
        String plans="SELECT shipment_line_id source_id,IF(state='RELEASED',accepted_quantity,quantity) quantity FROM oms_refund_return_plan WHERE company_code=? AND shipment_line_id IN ("+marks+")";
        String exchanges="SELECT source_shipment_line_id source_id,requested_quantity quantity FROM oms_exchange WHERE company_code=? AND source_shipment_line_id IN ("+marks+") AND id<>? AND approved_version IS NOT NULL AND state NOT IN ('REJECTED','CANCELED')";
        String tasks="SELECT shipment_line_id source_id,quantity FROM oms_refund_goods_task WHERE company_code=? AND shipment_line_id IN ("+marks+") AND refund_id<>? AND action IN ('RETURN','INTERCEPT','REFUSAL') AND state<>'CANCELED' AND return_plan_id IS NULL";
        String bundles="SELECT x.shipment_line_id source_id,x.quantity FROM oms_refund_bundle_claim x WHERE x.company_code=? AND x.shipment_line_id IN ("+marks+") AND x.refund_id<>? AND NOT EXISTS(SELECT 1 FROM oms_refund_return_plan p WHERE p.company_code=x.company_code AND p.refund_id=x.refund_id AND p.shipment_line_id=x.shipment_line_id)";
        for(int part=0;part<4;part++){args.add(c);for(Map<String,Object> s:sources)args.add(s.get(idKey));if(part==1)args.add(excludedExchange);if(part>=2)args.add(ownRefund);}
        Map<Long,Long> claimed=new HashMap<>();for(Map<String,Object> row:r.db().query("SELECT source_id,SUM(quantity) quantity FROM ("+plans+" UNION ALL "+exchanges+" UNION ALL "+tasks+" UNION ALL "+bundles+") physical_claims GROUP BY source_id",ROW,args.toArray()))claimed.put(number(row,"source_id"),number(row,"quantity"));
        for(Map<String,Object> s:sources)s.put("claimable_quantity",Math.max(0,number(s,"shipped_quantity")-claimed.getOrDefault(number(s,idKey),0L)));
    }
    /** A current, explicit return-only plan may coexist with another disjoint exchange source claim. */
    static String reviewedReturnOnly(String b){return "("+b+".review_state='APPROVED' AND "+b+".goods_action='MIXED' AND EXISTS(SELECT 1 FROM oms_platform_refund rp WHERE rp.company_code="+b+".company_code AND rp.id="+b+".platform_refund_id AND rp.source_state='VALID' AND rp.version="+b+".approved_version AND rp.platform_status<>'CLOSED') AND EXISTS(SELECT 1 FROM oms_refund_goods_task rt WHERE rt.company_code="+b+".company_code AND rt.refund_id="+b+".id AND rt.state<>'CANCELED') AND NOT EXISTS(SELECT 1 FROM oms_refund_goods_task rt WHERE rt.company_code="+b+".company_code AND rt.refund_id="+b+".id AND rt.state<>'CANCELED' AND (rt.action<>'RETURN' OR rt.shipment_line_id IS NULL)))";}
}
