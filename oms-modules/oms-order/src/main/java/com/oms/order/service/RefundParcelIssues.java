package com.oms.order.service;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Bounded batch projection of parcel tasks, separate from funds and warehouse receipts. */
final class RefundParcelIssues {
    static class Issue extends IllegalArgumentException {final Map<String,Object> detail;Issue(Map<String,Object> detail){super(text(detail,"reason"));this.detail=detail;}}
    static Map<String,Object> afterReceipt(Map<String,Object> p){return m("code","APPEND_AFTER_RECEIPT","title","合包追加待核对","reason","目标包裹已有实收回传，已停止自动追加；本退款尚未加入该包裹","next_action","核实本退款商品是否确实在原包裹中，再选择补充关联、更正物流或等待寄回","target_parcel_id",p.get("id"),"target_parcel_sn",p.get("parcel_sn"),"carrier_code",p.get("carrier_code"),"waybill",p.get("waybill"),"store_code",p.get("store_code"));}
    static void decorate(OrderRepository r,String c,List<Map<String,Object>> refunds){if(refunds.isEmpty())return;List<Object> args=new ArrayList<>();args.add(c);Map<Long,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> b:refunds){byId.put(number(b,"id"),b);b.put("parcel_issues",new ArrayList<>());b.put("parcel_followups",new ArrayList<>());args.add(b.get("id"));}
        for(Map<String,Object> p:r.db().query("SELECT id plan_id,refund_id,quantity,accepted_quantity,posted_quantity,parcel_state,parcel_error,parcel_issue,parcel_resolution,parcel_manual_dispatch,state,IF(result_json IS NULL,0,1) has_document,control_version plan_version FROM oms_refund_return_plan WHERE company_code=? AND refund_id IN ("+String.join(",",Collections.nCopies(refunds.size(),"?"))+") AND state NOT IN ('CLOSED','RELEASED') AND (parcel_state='NEED_CHECK' OR parcel_resolution IN ('WAREHOUSE_CHECK','WAIT_BUYER') OR (parcel_manual_dispatch=1 AND state='PLANNED')) ORDER BY id LIMIT 5001",ROW,args.toArray())){
            require(number(p,"plan_id")>0,"退回计划标识无效");
            if(!"NEED_CHECK".equals(text(p,"parcel_state"))&&!"WAREHOUSE_CHECK".equals(text(p,"parcel_resolution"))){@SuppressWarnings("unchecked") List<Map<String,Object>> followups=(List<Map<String,Object>>)byId.get(number(p,"refund_id")).get("parcel_followups");boolean manual=number(p,"parcel_manual_dispatch")==1;followups.add(m("plan_id",p.get("plan_id"),"title",manual?"补充关联完成，待下发THK":"等待买家寄回","next_action",manual?"人工下发本退款补充退回单，仓库受理后再明确分配原实收；关联未增加库存":"登记本退款真实寄回物流；已暂停使用原平台运单自动关联","manual_dispatch",manual));continue;}
Map<String,Object> issue=p.get("parcel_issue")==null?m("code","RULE_BLOCKED","title","合包待核对","reason",p.get("parcel_error"),"next_action","核对店铺规则、物流及仓库能力后重新检查"):obj(p.get("parcel_issue"));issue.putAll(m("plan_id",p.get("plan_id"),"plan_version",p.get("plan_version"),"quantity",p.get("quantity"),"accepted_quantity",p.get("accepted_quantity"),"posted_quantity",p.get("posted_quantity"),"resolution",p.get("parcel_resolution"),"has_document",number(p,"has_document")==1));
            @SuppressWarnings("unchecked") List<Map<String,Object>> issues=(List<Map<String,Object>>)byId.get(number(p,"refund_id")).get("parcel_issues");issues.add(issue);
        }
    }
}
