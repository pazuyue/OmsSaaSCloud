package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Bounded local projection by original SALE line. Never consults remote WMS from a list/detail read. */
final class RefundFulfillment {
    private RefundFulfillment() {}
    static final int LIMIT=5000;
    static String filter(String value){
        require(Arrays.asList("UNSHIPPED","PART_SHIPPED","SHIPPED","CANCELED","UNKNOWN").contains(value),"实际履约阶段无效");
        String lines=" FROM oms_order_line fl WHERE fl.company_code=b.company_code AND fl.order_id=b.order_id AND fl.platform_oid=b.platform_oid AND fl.business_type='SALE' AND fl.gift_plan_id IS NULL AND fl.exchange_id IS NULL";
        String source=" FROM oms_order_line fl JOIN oms_order_shipment_line fs ON fs.company_code=fl.company_code AND fs.order_line_id=fl.id JOIN oms_order_shipment fh ON fh.company_code=fs.company_code AND fh.id=fs.shipment_id WHERE fl.company_code=b.company_code AND fl.order_id=b.order_id AND fl.platform_oid=b.platform_oid AND fl.business_type='SALE' AND fl.gift_plan_id IS NULL AND fl.exchange_id IS NULL AND fh.business_type='SALE' AND fh.exchange_id IS NULL";
        String required="(SELECT COALESCE(SUM(fl.quantity-fl.cancelled_quantity),0)"+lines+")",shipped="(SELECT COALESCE(SUM(fs.shipped_quantity),0)"+source+")";
        String uncertain="EXISTS(SELECT 1"+source+" AND (fh.status IN ('DISPATCHING','VOIDING') OR EXISTS(SELECT 1 FROM oms_order_shipment_dispatch fd WHERE fd.company_code=fh.company_code AND fd.shipment_id=fh.id AND fd.state IN ('PROCESSING','UNKNOWN')) OR EXISTS(SELECT 1 FROM oms_order_shipment_outbound fo WHERE fo.company_code=fh.company_code AND fo.shipment_id=fh.id AND fo.state<>'COMPLETED')))";
        if("UNKNOWN".equals(value))return " AND ("+uncertain+" OR NOT EXISTS(SELECT 1"+lines+") OR "+shipped+">"+required+")";
        String condition="CANCELED".equals(value)?required+"=0 AND "+shipped+"=0":"UNSHIPPED".equals(value)?required+">0 AND "+shipped+"=0":"SHIPPED".equals(value)?required+">0 AND "+shipped+"="+required:shipped+">0 AND "+shipped+"<"+required;
        return " AND NOT "+uncertain+" AND EXISTS(SELECT 1"+lines+") AND ("+condition+")";
    }
    static String stage(long required,long shipped,boolean uncertain){
        if(uncertain||required<0||shipped<0||shipped>required)return "UNKNOWN";
        if(required==0)return "CANCELED";
        return shipped==0?"UNSHIPPED":shipped<required?"PART_SHIPPED":"SHIPPED";
    }
    static Map<String,Object> summarize(List<Map<String,Object>> lines,List<Map<String,Object>> shipments,boolean truncated){
        Map<Long,Map<String,Object>> indexed=new LinkedHashMap<>();long required=0,shipped=0,warehouse=0;boolean uncertain=truncated;
        for(Map<String,Object> line:lines){line.put("shipped_quantity",0L);line.put("unshipped_quantity",Math.max(0,number(line,"quantity")-number(line,"cancelled_quantity")));line.put("sources",new ArrayList<Map<String,Object>>());indexed.put(number(line,"order_line_id"),line);required+=number(line,"quantity")-number(line,"cancelled_quantity");}
        for(Map<String,Object> source:shipments){Map<String,Object> line=indexed.get(number(source,"order_line_id"));if(line==null){uncertain=true;continue;}
            long actual=number(source,"shipped_quantity"),left=Math.max(0,number(source,"quantity")-actual-number(source,"cancelled_quantity"));
            String dispatch=text(source,"dispatch_state"),status=text(source,"status");boolean unknown=number(source,"unconfirmed_outbound")>0||Arrays.asList("PROCESSING","UNKNOWN").contains(dispatch)||"DISPATCHING".equals(status)||"VOIDING".equals(status);
            String action=actual>0?"CHECK_PACKAGE":"";
            if(left>0)action=unknown?"QUERY_FIRST":"ACCEPTED".equals(dispatch)||"WAREHOUSE_PROCESSING".equals(status)||"PART_SHIPPED".equals(status)?"CANCEL_WAREHOUSE":"WITHDRAW_LOCAL";
            source.put("remaining_quantity",left);source.put("result_uncertain",unknown);source.put("suggested_action",action);source.put("shared_shipment",number(source,"other_unshipped_quantity")>0);
            ((List<Map<String,Object>>)line.get("sources")).add(source);line.put("shipped_quantity",number(line,"shipped_quantity")+actual);shipped+=actual;uncertain|=unknown;
            if(left>0&&("ACCEPTED".equals(dispatch)||"WAREHOUSE_PROCESSING".equals(status)||"PART_SHIPPED".equals(status)))warehouse+=left;
        }
        for(Map<String,Object> line:lines){long demand=number(line,"quantity")-number(line,"cancelled_quantity"),actual=number(line,"shipped_quantity");line.put("unshipped_quantity",Math.max(0,demand-actual));line.put("stage",stage(demand,actual,uncertain));if(actual>demand)uncertain=true;}
        return m("stage",stage(required,shipped,uncertain),"required_quantity",required,"shipped_quantity",shipped,"unshipped_quantity",Math.max(0,required-shipped),"warehouse_pending_quantity",warehouse,"uncertain",uncertain,"truncated",truncated,"quantity_unit","实际商品单位，套装与福袋按原履约组件分别核对","lines",lines);
    }
    static void decorate(OrderRepository r,String company,List<Map<String,Object>> refunds){
        if(refunds.isEmpty())return;require(refunds.size()<=100,"退款履约查询范围过大");
        String marks=String.join(",",Collections.nCopies(refunds.size(),"?"));List<Object> args=new ArrayList<>();args.add(company);for(Map<String,Object> b:refunds)args.add(b.get("id"));
        String scope=" FROM oms_refund b JOIN oms_order_line ol ON ol.company_code=b.company_code AND ol.order_id=b.order_id AND ol.platform_oid=b.platform_oid WHERE b.company_code=? AND b.id IN ("+marks+") AND ol.business_type='SALE' AND ol.gift_plan_id IS NULL AND ol.exchange_id IS NULL";
        List<Map<String,Object>> allLines=r.db().query("SELECT b.id refund_scope_id,ol.id order_line_id,ol.sku_sn,ol.goods_name,ol.quantity,ol.cancelled_quantity,ol.bundle_id,IF(ol.stocked_bundle_snapshot IS NULL,'件','套') stock_unit"+scope+" ORDER BY b.id,ol.id LIMIT 5001",ROW,args.toArray());
        String sourceScope=scope.replace(" WHERE", " JOIN oms_order_shipment_line sl ON sl.company_code=ol.company_code AND sl.order_line_id=ol.id JOIN oms_order_shipment sh ON sh.company_code=sl.company_code AND sh.id=sl.shipment_id LEFT JOIN oms_order_shipment_dispatch d ON d.company_code=sh.company_code AND d.shipment_id=sh.id WHERE");
        List<Map<String,Object>> allSources=r.db().query("SELECT b.id refund_scope_id,sl.order_line_id,sl.id shipment_line_id,sh.id shipment_id,sh.shipment_sn,sh.status,sl.store_code,sl.quantity,sl.shipped_quantity,sl.cancelled_quantity,COALESCE(d.state,'NONE') dispatch_state,"+
            "(SELECT COUNT(*) FROM oms_order_shipment_outbound o WHERE o.company_code=sh.company_code AND o.shipment_id=sh.id AND o.state<>'COMPLETED') unconfirmed_outbound,"+
            "(SELECT COALESCE(SUM(GREATEST(x.quantity-x.shipped_quantity-x.cancelled_quantity,0)),0) FROM oms_order_shipment_line x JOIN oms_order_line xl ON xl.company_code=x.company_code AND xl.id=x.order_line_id WHERE x.company_code=sh.company_code AND x.shipment_id=sh.id AND (xl.platform_oid<>b.platform_oid OR xl.gift_plan_id IS NOT NULL OR xl.exchange_id IS NOT NULL)) other_unshipped_quantity"+
            sourceScope+" AND sh.business_type='SALE' AND sh.exchange_id IS NULL ORDER BY b.id,sh.id,sl.id LIMIT 5001",ROW,args.toArray());
        boolean truncated=allLines.size()>LIMIT||allSources.size()>LIMIT;Map<Long,List<Map<String,Object>>> lines=new HashMap<>(),sources=new HashMap<>();
        for(Map<String,Object> line:allLines)lines.computeIfAbsent(number(line,"refund_scope_id"),key->new ArrayList<>()).add(line);
        for(Map<String,Object> source:allSources)sources.computeIfAbsent(number(source,"refund_scope_id"),key->new ArrayList<>()).add(source);
        for(Map<String,Object> b:refunds){long id=number(b,"id");Map<String,Object> context=summarize(lines.getOrDefault(id,new ArrayList<>()),sources.getOrDefault(id,new ArrayList<>()),truncated);if(((List<?>)context.get("lines")).isEmpty()){context.put("stage","UNKNOWN");context.put("uncertain",true);}b.put("fulfillment",context);b.put("fulfillment_stage",context.get("stage"));}
    }
    static Map<String,Object> context(OrderRepository r,String company,Map<String,Object> b){decorate(r,company,Collections.singletonList(b));return obj(b.get("fulfillment"));}
    static void capture(OrderRepository r,String company,Map<String,Object> b){Map<String,Object> context=context(r,company,b);r.db().update("UPDATE oms_refund SET initial_fulfillment_snapshot=?,initial_fulfillment_stage=? WHERE company_code=? AND id=? AND initial_fulfillment_snapshot IS NULL",encode(context),context.get("stage"),company,b.get("id"));}
}
