package com.oms.order.service;

import java.math.BigDecimal;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Read-only commercial display. No locks, remote calls or inferred per-unit payment allocation. */
final class RefundAmountContext {
    private RefundAmountContext() {}
    private static String confirmed(){return "CASE WHEN x.source_state='VALID' AND x.platform_status='SUCCESS' THEN x.refund_fee WHEN y.funds_state='SUCCESS' THEN y.refund_fee ELSE 0 END";}
    private static String occupied(){return "CASE WHEN COALESCE(y.funds_state,'')<>'SUCCESS' AND x.platform_status NOT IN ('SUCCESS','CLOSED') AND y.review_state='APPROVED' THEN y.refund_fee ELSE 0 END";}
    static Map<String,Object> read(OrderRepository r,String c,Map<String,Object> b,Map<String,Object> platform,Map<String,Object> sales){
        String confirmed=confirmed(),occupied=occupied();
        Map<String,Object> total=r.db().queryForMap("SELECT COALESCE(SUM("+confirmed+"),0) confirmed_total,"+
            "COALESCE(SUM(IF(x.id<>?,"+confirmed+",0)),0) other_confirmed,"+
            "COALESCE(SUM(IF(x.id<>?,"+occupied+",0)),0) other_occupied,"+
            "COALESCE(SUM(IF(x.id=?,"+confirmed+",0)),0) current_confirmed,"+
            "COALESCE(SUM(x.source_state<>'VALID' OR (COALESCE(y.review_state,'')='STALE' AND COALESCE(y.funds_state,'')<>'SUCCESS')),0) uncertain_count,"+
            "COALESCE(SUM(y.purpose='POSTAGE' AND (y.funds_state='SUCCESS' OR (y.review_state='APPROVED' AND x.platform_status<>'CLOSED'))),0) postage_count "+
            "FROM oms_platform_refund x LEFT JOIN oms_refund y ON y.company_code=x.company_code AND y.platform_refund_id=x.id "+
            "WHERE x.company_code=? AND x.channel_id=? AND x.platform_tid=? AND x.platform_oid=?",
            b.get("platform_refund_id"),b.get("platform_refund_id"),b.get("platform_refund_id"),c,b.get("channel_id"),b.get("platform_tid"),b.get("platform_oid"));
        BigDecimal paid=sales.get("goods_payment")==null?null:money(sales.get("goods_payment"));
        BigDecimal succeeded=money(total.get("confirmed_total")),other=money(total.get("other_confirmed")),occupiedByOthers=money(total.get("other_occupied"));
        boolean authoritative=paid!=null&&text(sales,"payment_source").startsWith("平台");
        boolean reliable=authoritative&&number(total,"uncertain_count")==0&&number(total,"postage_count")==0&&succeeded.compareTo(paid)<=0;
        String issue=!authoritative?"原子单商品实付尚无明确平台依据，额度待核对":number(total,"uncertain_count")>0?"同一子订单有来源或方案待核对的退款，额度暂不确认":number(total,"postage_count")>0?"该子单涉及运费退款，商品与运费金额需拆分核对":succeeded.compareTo(paid)>0?"累计退款超过原商品实付，请核对资金与运费拆分":"";
        boolean whole=number(sales,"quantity")>0&&number(platform,"platform_quantity")==number(sales,"quantity");
        return m("scope","PLATFORM_SUBORDER","original_goods_payment",paid,"payment_source",sales.get("payment_source"),"authoritative",authoritative,"reliable",reliable,
            "original_quantity",sales.get("quantity"),"requested_goods_payment",whole&&authoritative?paid:null,
            "requested_payment_note",whole&&authoritative?"申请数量覆盖原子单全部商品，金额不含买家运费":"平台未提供本次申请数量对应的商品实付，不按平均单价推算",
            "current_confirmed",total.get("current_confirmed"),"confirmed_total",succeeded,"other_confirmed",other,"other_occupied",occupiedByOthers,
            "current_limit",reliable?paid.subtract(other).subtract(occupiedByOthers).max(BigDecimal.ZERO):null,
            "remaining_unpaid",reliable?paid.subtract(succeeded).max(BigDecimal.ZERO):null,"issue",issue,
            "note","金额按本平台子订单统计；本次上限扣除其他退款，累计已退包含本单成功金额。实际审核、付款时重新校验。");
    }
    /** Loaded only on demand. Same indexed suborder and monetary predicates as the summary. */
    static Map<String,Object> records(OrderRepository r,String c,Map<String,Object> b,int page,int size){
        require(page>=1&&page<=100000&&size>=1&&size<=50,"退款明细分页参数无效");
        List<Map<String,Object>> rows=r.db().query("SELECT x.id platform_refund_id,x.refund_id,x.platform_status,x.source_state,x.create_time,"+
            "y.id business_refund_id,y.refund_sn,y.purpose,y.review_state,y.funds_state,"+confirmed()+" confirmed_amount,"+occupied()+" occupied_amount "+
            "FROM oms_platform_refund x LEFT JOIN oms_refund y ON y.company_code=x.company_code AND y.platform_refund_id=x.id "+
            "WHERE x.company_code=? AND x.channel_id=? AND x.platform_tid=? AND x.platform_oid=? AND x.id<>? AND ("+confirmed()+">0 OR "+occupied()+">0) "+
            "ORDER BY x.id DESC LIMIT ? OFFSET ?",ROW,c,b.get("channel_id"),b.get("platform_tid"),b.get("platform_oid"),b.get("platform_refund_id"),size+1,(page-1)*size);
        boolean more=rows.size()>size;if(more)rows.remove(rows.size()-1);
        for(Map<String,Object> row:rows){row.put("confirmed_amount",money(row.get("confirmed_amount")));row.put("occupied_amount",money(row.get("occupied_amount")));}
        return m("rows",rows,"has_more",more,"page_num",page,"page_size",size,"platform_oid",b.get("platform_oid"));
    }
}
