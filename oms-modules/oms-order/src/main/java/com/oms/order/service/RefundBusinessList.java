package com.oms.order.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Read-only page enrichment. No order assessments, remote calls or per-row queries. */
final class RefundBusinessList {
    private RefundBusinessList() {}

    private static String receiptPending(String alias){return RefundReturnSummary.pending(alias);}
    private static String giftPending(){return RefundReturnSummary.giftPending();}

    static String filters(String where,List<Object> args,Map<String,String> query) {
        String task=query.getOrDefault("task","");
        switch(task) {
            case "": case "ALL": break;
            case "REVIEW": where+=" AND b.review_state<>'APPROVED' AND b.state NOT IN ('COMPLETED','CLOSED','CANCELED')"; break;
            case "GOODS": where+=" AND b.review_state='APPROVED' AND b.goods_action<>'KEEP' AND b.goods_state NOT IN ('DONE','CANCELED')"; break;
            case "GIFT": where+=" AND "+giftPending(); break;
            case "ISSUE": where+=" AND (b.state='BLOCKED' OR b.problem_message<>'' OR EXISTS(SELECT 1 FROM oms_refund_goods_task gt WHERE gt.company_code=b.company_code AND gt.refund_id=b.id AND gt.state='MANUAL') OR EXISTS (SELECT 1 FROM oms_refund_return_auto_task at WHERE at.company_code=b.company_code AND at.refund_id=b.id AND at.state='MANUAL' AND b.goods_state NOT IN ('DONE','CANCELED')) OR EXISTS (SELECT 1 FROM oms_refund_return_plan ip WHERE ip.company_code=b.company_code AND ip.refund_id=b.id AND ("+receiptPending("ip")+" OR (ip.state='PLANNED' AND ip.auto_dispatch_state IN ('FAILED','MANUAL')))))"; break;
            case "DONE": where+=" AND b.state='COMPLETED' AND NOT "+giftPending(); break;
            default: throw new IllegalArgumentException("待办类型无效");
        }
        String fulfillment=query.getOrDefault("fulfillmentStage","");if(!fulfillment.isEmpty())where+=RefundFulfillment.filter(fulfillment);
        String scenario=query.getOrDefault("scenario","");
        switch(scenario){
            case "": break;
            case "REFUND_RETURN": where+=" AND pr.dispute_type='REFUND' AND "+RefundReturnSummary.purchase();break;
            case "REFUND_GIFT_ONLY": where+=" AND pr.dispute_type='REFUND' AND NOT "+RefundReturnSummary.purchase()+" AND "+RefundReturnSummary.plan(true,"");break;
            case "PAID_PENDING": where+=" AND b.funds_state='SUCCESS' AND ("+RefundReturnSummary.purchasePending()+" OR "+RefundReturnSummary.giftPending()+")";break;
            case "MISSING_DOCUMENT": where+=" AND "+RefundReturnSummary.missingDocument();break;
            case "UNPOSTED": where+=" AND EXISTS (SELECT 1 FROM oms_refund_return_plan up WHERE up.company_code=b.company_code AND up.refund_id=b.id AND up.accepted_quantity>up.posted_quantity)";break;
            default: throw new IllegalArgumentException("业务场景无效");
        }
        where=choice(where,args,query,"goodsAction","b.goods_action",Arrays.asList("KEEP","CANCEL","RETURN","REFUSAL","INTERCEPT","MIXED"));
        where=choice(where,args,query,"fundsState","b.funds_state",Arrays.asList("NONE","SUCCESS","CLOSED"));
        where=choice(where,args,query,"goodsState","b.goods_state",Arrays.asList("WAIT_REVIEW","UNKNOWN","WAIT_ACTION","WAIT_RETURN","PART_RETURNED","WAIT_CHECK","WAIT_QC","DONE","CANCELED"));
        String from=query.getOrDefault("createdFrom",""),to=query.getOrDefault("createdTo","");
        try {
            LocalDate begin=from.isEmpty()?null:LocalDate.parse(from),end=to.isEmpty()?null:LocalDate.parse(to);
            require(begin==null||end==null||!begin.isAfter(end),"创建时间范围无效");
            if(begin!=null){where+=" AND b.create_time>=?";args.add(begin+" 00:00:00");}
            if(end!=null){where+=" AND b.create_time<?";args.add(end.plusDays(1)+" 00:00:00");}
        } catch(DateTimeParseException e){throw new IllegalArgumentException("创建日期格式无效");}
        return RefundLogisticsSummary.filter(where,query);
    }
    private static String choice(String where,List<Object> args,Map<String,String> query,String key,String field,List<String> allowed) {
        String value=query.getOrDefault(key,"");if(value.isEmpty())return where;
        require(allowed.contains(value),"筛选状态无效");args.add(value);return where+" AND "+field+"=?";
    }

    static void decorate(OrderRepository repository,String company,List<Map<String,Object>> rows) {
        if(rows.isEmpty())return;
        RefundFulfillment.decorate(repository,company,rows);
        RefundReturnAutoCreate.decorate(repository,company,rows);
        List<Object> args=new ArrayList<>();args.add(company);
        Map<Long,Map<String,Object>> byId=new LinkedHashMap<>();
        for(Map<String,Object> row:rows){byId.put(number(row,"id"),row);args.add(row.get("id"));row.put("application_products",new ArrayList<>());row.put("gift_pending",false);}
        String marks=String.join(",",Collections.nCopies(rows.size(),"?"));
        RefundReturnSummary.decorate(repository,company,rows);

        RefundLogisticsSummary.decorate(repository,company,rows);
        Map<Long,Set<String>> seen=new HashMap<>();
        for(Map<String,Object> product:repository.db().query("SELECT b.id refund_id,l.id,l.bundle_id,COALESCE(k.sku_sn,l.sku_sn) sku_sn,COALESCE(k.goods_name,l.goods_name) goods_name,IF(k.id IS NULL,0,1) is_bundle,k.business_type sales_business_type FROM oms_refund b JOIN oms_order_line l ON l.company_code=b.company_code AND l.order_id=b.order_id AND l.platform_oid=b.platform_oid LEFT JOIN oms_order_bundle k ON k.company_code=l.company_code AND k.id=l.bundle_id WHERE b.company_code=? AND b.id IN ("+marks+") AND l.gift_plan_id IS NULL AND l.exchange_id IS NULL AND l.business_type<>'EXCHANGE' ORDER BY b.id,l.id",ROW,args.toArray())) {
            long id=number(product,"refund_id");String key=number(product,"is_bundle")>0?"B"+product.get("bundle_id"):"L"+product.get("id");
            if(!seen.computeIfAbsent(id,keyId->new HashSet<>()).add(key))continue;
            @SuppressWarnings("unchecked") List<Map<String,Object>> products=(List<Map<String,Object>>)byId.get(id).get("application_products");products.add(product);
        }
    }
}
