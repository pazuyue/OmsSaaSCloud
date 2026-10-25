package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Shared predicates for page filters and detail summaries. Each lookup uses company/refund indexes. */
final class RefundReturnSummary {
    private RefundReturnSummary(){}
    // A changed buyer waybill remains traceable after its original THK has received and posted
    // the complete plan. It no longer represents an unallocated receipt or outstanding stock.
    static String parcelPending(String a){return "("+a+".parcel_state='NEED_CHECK' AND NOT ("+a+".state='CLOSED' AND "+a+".quantity>0 AND "+a+".quantity="+a+".accepted_quantity AND "+a+".accepted_quantity="+a+".posted_quantity AND "+a+".waived_quantity=0 AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT("+a+".parcel_issue,'$.code')),'')='LOGISTICS_CHANGED'))";}
    static String pending(String a){return "("+a+".state='WAIT_REVIEW' OR "+parcelPending(a)+" OR "+a+".parcel_resolution='WAREHOUSE_CHECK' OR COALESCE(JSON_EXTRACT("+a+".result_json,'$.pending_review_count'),0)>0 OR COALESCE(JSON_EXTRACT("+a+".result_json,'$.parcel_pending_count'),0)>0)";}
    static String unfinished(String a){return "("+a+".quantity>"+a+".posted_quantity+"+a+".waived_quantity OR "+a+".accepted_quantity<>"+a+".posted_quantity OR "+pending(a)+")";}
    static String effective(String a){return "("+a+".accepted_quantity>0 OR "+a+".posted_quantity>0 OR "+pending(a)+" OR ("+a+".state NOT IN ('CLOSED','CANCELED','RELEASED','STOPPED') AND "+a+".quantity>"+a+".waived_quantity))";}
    static String document(String a){return "(JSON_EXTRACT("+a+".result_json,'$.id') IS NOT NULL OR COALESCE(JSON_LENGTH(JSON_EXTRACT("+a+".result_json,'$.documents')),0)>0 OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT("+a+".result_json,'$.return_sn')),'')<>'')";}
    static String plan(boolean gift,String extra){return "EXISTS (SELECT 1 FROM oms_refund_return_plan rp WHERE rp.company_code=b.company_code AND rp.refund_id=b.id AND rp.gift_plan_id IS "+(gift?"NOT NULL":"NULL")+" AND "+effective("rp")+(extra.isEmpty()?"":" AND "+extra)+")";}
    static String intent(){return "(b.review_state='APPROVED' AND (b.goods_action IN ('RETURN','REFUSAL','INTERCEPT') OR b.goods_action='MIXED' AND EXISTS(SELECT 1 FROM oms_refund_goods_task gt WHERE gt.company_code=b.company_code AND gt.refund_id=b.id AND gt.action IN ('RETURN','REFUSAL','INTERCEPT') AND gt.state NOT IN ('DONE','CANCELED'))) AND b.goods_state NOT IN ('DONE','CANCELED') AND b.state NOT IN ('CLOSED','CANCELED'))";}
    static String purchase(){return "("+intent()+" OR "+plan(false,"")+")";}
    static String purchasePending(){return "("+intent()+" OR "+plan(false,unfinished("rp"))+")";}
    static String giftPending(){return plan(true,unfinished("rp"));}
    static String missingDocument(){return "("+purchasePending()+" AND NOT "+plan(false,document("rp"))+")";}
    static String columns(){return ","+purchase()+" has_purchase_return,"+plan(true,"")+" has_gift_return,"+purchasePending()+" purchase_return_pending,"+missingDocument()+" missing_purchase_document";}
    static void decorate(OrderRepository repository,String company,List<Map<String,Object>> rows){
        if(rows.isEmpty())return;List<Object> args=new ArrayList<>();args.add(company);Map<Long,Map<String,Object>> byId=new LinkedHashMap<>();for(Map<String,Object> row:rows){byId.put(number(row,"id"),row);args.add(row.get("id"));row.put("gift_pending",false);}
        String marks=String.join(",",Collections.nCopies(rows.size(),"?")),a="rp",effective=effective(a),pending=pending(a);
        String relevant="("+effective+" OR rp.waived_quantity>0 OR (rp.state='STOPPED' AND rp.quantity>0))";
        String query="SELECT rp.refund_id,IF(rp.gift_plan_id IS NULL,'PURCHASE','GIFT') item_kind,IF(ol.stocked_bundle_snapshot IS NULL,'件','套') physical_unit,COUNT(*) plan_count,SUM("+effective+") effective_count,SUM("+document(a)+") document_count,SUM(rp.state='STOPPED') stopped_count,SUM(rp.quantity) return_quantity,SUM(rp.accepted_quantity) accepted_quantity,SUM(rp.posted_quantity) posted_quantity,SUM(rp.waived_quantity) waived_quantity,SUM(IF(rp.state IN ('CLOSED','CANCELED','RELEASED'),0,GREATEST(rp.quantity-rp.accepted_quantity-rp.waived_quantity,0))) remaining_quantity,SUM(GREATEST(rp.accepted_quantity-rp.posted_quantity,0)) unposted_quantity,SUM("+pending+") pending_count,SUM((rp.state NOT IN ('CLOSED','CANCELED','RELEASED') AND "+unfinished(a)+") OR "+pending+") unfinished_count,SUM("+parcelPending(a)+") parcel_issues,SUM(rp.parcel_state='WAIT_LOGISTICS') parcel_waiting,SUM(rp.return_mode='INTERCEPT' AND "+effective+") intercept_count,SUM(rp.parcel_state='LINKED') linked_count FROM oms_refund_return_plan rp LEFT JOIN oms_order_shipment_line sl ON sl.company_code=rp.company_code AND sl.id=rp.shipment_line_id LEFT JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE rp.company_code=? AND rp.refund_id IN ("+marks+") AND "+relevant+" GROUP BY rp.refund_id,item_kind,physical_unit";
        for(Map<String,Object> progress:repository.db().query(query,ROW,args.toArray())){
            Map<String,Object> row=byId.get(number(progress,"refund_id"));boolean gift="GIFT".equals(progress.get("item_kind"));String key=gift?"gift_progress":"purchase_progress";
            Map<String,Object> aggregate=(Map<String,Object>)row.computeIfAbsent(key,k->{Map<String,Object> value=new LinkedHashMap<>();value.put("unit_progress",new ArrayList<>());return value;});
            ((List<Map<String,Object>>)aggregate.get("unit_progress")).add(progress);
            for(String field:Arrays.asList("plan_count","effective_count","document_count","stopped_count","return_quantity","accepted_quantity","posted_quantity","waived_quantity","remaining_quantity","unposted_quantity","pending_count","unfinished_count","parcel_issues","parcel_waiting","intercept_count","linked_count"))aggregate.put(field,number(aggregate,field)+number(progress,field));
            if(gift)row.put("gift_pending",number(aggregate,"unfinished_count")>0);
        }

        for(Map<String,Object> row:rows){Map<String,Object> total=new LinkedHashMap<>();for(String key:Arrays.asList("return_quantity","accepted_quantity","posted_quantity","waived_quantity","parcel_issues","parcel_waiting")){long value=0;for(String kind:Arrays.asList("purchase_progress","gift_progress")){Object p=row.get(kind);if(p instanceof Map)value+=number((Map<String,Object>)p,key);}total.put(key,value);}row.put("return_progress",total);}
    }
}
