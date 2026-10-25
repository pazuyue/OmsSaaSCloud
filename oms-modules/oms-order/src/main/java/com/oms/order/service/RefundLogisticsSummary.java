package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Local, bounded list projection. Platform acceptance and declared logistics outcomes stay separate. */
final class RefundLogisticsSummary {
    private RefundLogisticsSummary() {}
    static final Set<String> OUTCOMES=new HashSet<>(Arrays.asList("DELIVERY_INTERCEPT_SUCCESS","DELIVERY_INTERCEPT_FAILED","BUYER_REFUSE_SIGN","LOGISTICS_RETURNING","RETURN_LOGISTIC_SELLER_CONSIGN"));
    static String provided(String key){return "COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(pr.payload),pr.payload,'{}'),'$.refund_get_response.refund."+key+"')),''),NULLIF(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(pr.payload),pr.payload,'{}'),'$.refund."+key+"')),''),NULLIF(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(pr.payload),pr.payload,'{}'),'$."+key+"')),''),'')";}
    static String intent(){return "(b.goods_action='INTERCEPT' OR EXISTS(SELECT 1 FROM oms_refund_goods_task lg WHERE lg.company_code=b.company_code AND lg.refund_id=b.id AND lg.action='INTERCEPT' AND lg.state<>'CANCELED') OR EXISTS(SELECT 1 FROM oms_refund_return_plan lp WHERE lp.company_code=b.company_code AND lp.refund_id=b.id AND lp.return_mode='INTERCEPT' AND "+RefundReturnSummary.effective("lp")+"))";}
    static String latest(String action,String condition){return "EXISTS(SELECT 1 FROM oms_refund_operation lo WHERE lo.company_code=b.company_code AND lo.refund_id=b.id AND lo.action='"+action+"' AND lo.id=(SELECT MAX(lx.id) FROM oms_refund_operation lx WHERE lx.company_code=lo.company_code AND lx.refund_id=lo.refund_id AND lx.action=lo.action)"+condition+")";}
    static String missingWaybill(){return "EXISTS(SELECT 1 FROM oms_refund_return_plan lw WHERE lw.company_code=b.company_code AND lw.refund_id=b.id AND lw.state NOT IN ('CLOSED','CANCELED','STOPPED','RELEASED') AND lw.quantity>lw.accepted_quantity+lw.waived_quantity AND lw.parcel_state<>'LINKED' AND NOT((lw.logistics_source='MANUAL' AND lw.return_waybill<>'' AND lw.return_carrier<>'') OR (lw.logistics_source<>'MANUAL' AND COALESCE(pr.source_state,'')='VALID' AND "+provided("sid")+"<>'' AND "+provided("company_name")+"<>'')))";}
    static String filter(String where,Map<String,String> q){
        String state=q.getOrDefault("logisticsState","");if(state.isEmpty())return where;
        String include=q.getOrDefault("includeCompletedLogistics","0");require(Arrays.asList("0","1").contains(include),"物流筛选范围无效");
        if(!"1".equals(include))where+=" AND b.state NOT IN ('COMPLETED','CLOSED','CANCELED')";
        switch(state){
            case "AWAIT_APPLY":return where+" AND "+intent()+" AND NOT "+latest("INTERCEPT","");
            case "APPLY_FAILED":return where+" AND "+intent()+" AND "+latest("INTERCEPT"," AND lo.state='FAILED'");
            case "RESULT_UNKNOWN":return where+" AND "+intent()+" AND NOT "+latest("INTERCEPT_FEEDBACK"," AND lo.state='SUCCESS' AND JSON_UNQUOTE(JSON_EXTRACT(lo.request_json,'$.logistic_status')) IN ('DELIVERY_INTERCEPT_SUCCESS','DELIVERY_INTERCEPT_FAILED','BUYER_REFUSE_SIGN','LOGISTICS_RETURNING','RETURN_LOGISTIC_SELLER_CONSIGN')");
            case "WAIT_WAYBILL":return where+" AND "+missingWaybill();
            case "WAIT_RECEIVE":return where+" AND EXISTS(SELECT 1 FROM oms_refund_return_plan lr WHERE lr.company_code=b.company_code AND lr.refund_id=b.id AND lr.state NOT IN ('CLOSED','CANCELED','STOPPED','RELEASED') AND lr.quantity>lr.accepted_quantity+lr.waived_quantity AND "+RefundReturnSummary.document("lr")+")";
            case "UNPOSTED":return where+" AND EXISTS(SELECT 1 FROM oms_refund_return_plan lu WHERE lu.company_code=b.company_code AND lu.refund_id=b.id AND lu.accepted_quantity>lu.posted_quantity)";
            default:throw new IllegalArgumentException("物流进度筛选无效");
        }
    }
    static List<Map<String,Object>> operations(OrderRepository r,String c,List<Long> ids){
        if(ids.isEmpty())return Collections.emptyList();List<Object> args=new ArrayList<>();args.add(c);args.addAll(ids);
        return r.db().query("SELECT o.refund_id,o.id,o.action,o.state,o.actor,o.reason,o.result_message,o.create_time,o.request_json FROM oms_refund_operation o WHERE o.company_code=? AND o.refund_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND o.action IN ('INTERCEPT','INTERCEPT_FEEDBACK') AND o.id=(SELECT MAX(x.id) FROM oms_refund_operation x WHERE x.company_code=o.company_code AND x.refund_id=o.refund_id AND x.action=o.action) ORDER BY o.id DESC",ROW,args.toArray());
    }
    static Map<String,Object> operation(Map<String,Object> op){return m("id",op.get("id"),"state",op.get("state"),"actor",op.get("actor"),"recorded_at",op.get("create_time"),"reason",op.get("reason"),"message",op.get("result_message"));}
    static Map<String,Object> project(boolean intercept,List<Map<String,Object>> operations){
        Map<String,Object> application=m(),outcome=m();for(Map<String,Object> op:operations){
            if("INTERCEPT".equals(text(op,"action"))){intercept=true;if(application.isEmpty())application=operation(op);}
            if("INTERCEPT_FEEDBACK".equals(text(op,"action"))&&outcome.isEmpty()){
                String status=text(obj(op.get("request_json")),"logistic_status");outcome=operation(op);outcome.putAll(m("logistic_status",OUTCOMES.contains(status)?status:"UNKNOWN","source","SELLER_FEEDBACK","confirmed",OUTCOMES.contains(status)&&"SUCCESS".equals(text(op,"state"))));
            }
        }
        return m("has_intercept",intercept,"application",application,"reported_outcome",outcome,"courier_query_supported",false,"scope","REFUND");
    }
    static void decorate(OrderRepository r,String c,List<Map<String,Object>> rows){
        List<Long> ids=new ArrayList<>();Map<Long,List<Map<String,Object>>> byId=new HashMap<>();for(Map<String,Object> row:rows)ids.add(number(row,"id"));
        for(Map<String,Object> op:operations(r,c,ids))byId.computeIfAbsent(number(op,"refund_id"),k->new ArrayList<>()).add(op);
        for(Map<String,Object> row:rows){
            boolean intercept=number(row,"has_intercept_intent")>0||"INTERCEPT".equals(text(row,"goods_action"));Map<String,Object> p=obj(row.get("purchase_progress")),g=obj(row.get("gift_progress"));
            intercept|=number(p,"intercept_count")+number(g,"intercept_count")>0;
            Map<String,Object> summary=project(intercept,byId.getOrDefault(number(row,"id"),Collections.emptyList()));
            long unposted=number(p,"unposted_quantity")+number(g,"unposted_quantity"),remaining=number(p,"remaining_quantity")+number(g,"remaining_quantity"),plans=number(p,"plan_count")+number(g,"plan_count"),linked=number(p,"linked_count")+number(g,"linked_count");
            summary.put("return_state",unposted>0?"UNPOSTED":plans>0&&remaining==0?"RECEIVED":linked>0?"LINKED":number(row,"missing_return_waybill")>0?"WAIT_WAYBILL":plans>0?"ARRANGED":"NONE");row.put("logistics_summary",summary);
        }
    }
}
