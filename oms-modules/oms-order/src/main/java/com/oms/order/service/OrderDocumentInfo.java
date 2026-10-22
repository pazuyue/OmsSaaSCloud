package com.oms.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Commercial facts are captured once, independently of exploded warehouse components.
 * Source: https://developer.alibaba.com/docs/doc.htm?articleId=108471&docType=1&treeId=780
 * Unknown amounts stay null. Promotion detail and allocated order discounts are never summed twice.
 */
public final class OrderDocumentInfo {
    private OrderDocumentInfo(){}
    static final List<String> META=Arrays.asList("created","pay_time","consign_time","end_time","buyer_message","seller_memo","invoice_name","invoice_type","invoice_tax_no","invoice_status");
    static final List<String> TRADE_MONEY=Arrays.asList("payment","post_fee","discount_fee","adjust_fee","total_fee","currency","promotion_details");
    static final List<String> LINE_MONEY=Arrays.asList("payment","divide_order_fee","price","discount_fee","part_mjz_discount","adjust_fee","total_fee");
    private static BigDecimal amount(JsonNode node,String key,boolean signed,List<String> issues,String scope){
        if(!node.hasNonNull(key)||node.path(key).asText().isEmpty())return null;
        try{BigDecimal v=new BigDecimal(node.path(key).asText());if(v.scale()>2||v.precision()>18||(!signed&&v.signum()<0))throw new NumberFormatException();return v.setScale(2);}catch(NumberFormatException e){issues.add(scope+"："+key+" 金额格式无效");return null;}
    }
    private static BigDecimal add(BigDecimal a,BigDecimal b){return a==null||b==null?null:a.add(b);}
    private static BigDecimal number(Map<String,Object> m,String key){Object n=m.get(key);return n instanceof BigDecimal?(BigDecimal)n:n==null?null:new BigDecimal(n.toString());}
    private static BigDecimal sum(List<Map<String,Object>> rows,String key){BigDecimal sum=BigDecimal.ZERO;for(Map<String,Object> row:rows){BigDecimal v=number(row,key);if(v==null)return null;sum=sum.add(v);}return rows.isEmpty()?null:sum;}
    private static void put(Map<String,Object> m,String key,BigDecimal v){m.put(key,v==null?null:v.setScale(2).toPlainString());}
    public static Map<String,Object> parse(JsonNode trade){
        Map<String,Object> r=new LinkedHashMap<>();List<String> issues=new ArrayList<>();r.put("schema_version",1);r.put("currency",trade.path("currency").asText("CNY"));
        for(String key:META){String v=trade.path(key).asText("");r.put(key,v.length()>4000?v.substring(0,4000):v);}
        r.put("source_modified",trade.path("modified").asText(""));r.put("metadata_modified",r.get("source_modified"));
        BigDecimal paid=amount(trade,"payment",false,issues,"订单"),freight=amount(trade,"post_fee",false,issues,"订单"),orderDiscount=amount(trade,"discount_fee",false,issues,"订单");
        put(r,"payment",paid);put(r,"buyer_freight",freight);put(r,"order_discount",orderDiscount);put(r,"platform_payable",amount(trade,"total_fee",false,issues,"订单"));
        put(r,"platform_adjustment",amount(trade,"adjust_fee",true,issues,"订单"));
        List<Map<String,Object>> rows=new ArrayList<>();JsonNode children=trade.path("orders").path("order");List<JsonNode> source=new ArrayList<>();if(children.isArray())children.forEach(source::add);else if(children.isObject())source.add(children);
        for(JsonNode line:source){
            Map<String,Object> row=new LinkedHashMap<>();row.put("platform_oid",line.path("oid").asText());row.put("quantity",line.path("num").asInt());
            String scope="子订单 "+line.path("oid").asText();BigDecimal price=amount(line,"price",false,issues,scope),discount=amount(line,"discount_fee",false,issues,scope),share=amount(line,"part_mjz_discount",false,issues,scope),adjust=amount(line,"adjust_fee",true,issues,scope);
            put(row,"sales_price",price);put(row,"sales_amount",price==null?null:price.multiply(BigDecimal.valueOf(line.path("num").asLong())));put(row,"item_discount",discount);put(row,"order_discount_share",share);put(row,"discount_total",add(discount,share));put(row,"adjustment",adjust);
            put(row,"platform_payment",amount(line,"payment",false,issues,scope));put(row,"divided_payment",amount(line,"divide_order_fee",false,issues,scope));put(row,"platform_payable",amount(line,"total_fee",false,issues,scope));rows.add(row);
        }
        BigDecimal divided=sum(rows,"divided_payment");
        List<String> differences=new ArrayList<>();
        for(Map<String,Object> row:rows){
            BigDecimal goods=null;String origin="未提供";
            // Only classify the allocated payment as goods when conservation proves freight is separate.
            if(divided!=null&&freight!=null&&paid!=null&&divided.add(freight).compareTo(paid)==0){goods=number(row,"divided_payment");origin="平台分摊实付（已核对不含运费）";}
            else if(rows.size()==1&&freight!=null&&paid!=null){BigDecimal value=number(row,"divided_payment");if(value==null)value=number(row,"platform_payment");if(value!=null&&value.compareTo(paid)==0){goods=value.subtract(freight);origin="平台子单实付扣除买家运费";}}
            BigDecimal base=add(number(row,"sales_amount"),number(row,"adjustment")),lineDiscount=number(row,"discount_total"),calculated=base==null||lineDiscount==null?null:base.subtract(lineDiscount);
            if(goods==null&&calculated!=null){goods=calculated;origin="销售金额＋子单改价－自身优惠－整单优惠分摊";}
            if(goods!=null&&goods.signum()<0){issues.add("子订单 "+row.get("platform_oid")+"：商品实付计算为负数，请核对运费及优惠");goods=null;}
            put(row,"goods_payment",goods);row.put("payment_source",origin);
            put(row,"calculated_goods_payment",calculated);BigDecimal delta=goods==null||calculated==null?null:calculated.subtract(goods);put(row,"payment_difference",delta);
            if(delta!=null&&delta.signum()!=0)differences.add("子订单 "+row.get("platform_oid")+"：计算商品实付与平台分摊商品实付差额 "+delta.toPlainString()+" 元");
        }
        put(r,"sales_amount",sum(rows,"sales_amount"));put(r,"item_discount",sum(rows,"item_discount"));put(r,"discount_total",add(sum(rows,"item_discount"),orderDiscount));put(r,"goods_payment",sum(rows,"goods_payment"));
        // Trade.adjust_fee may repeat the single child adjustment; use children only in this equation.
        BigDecimal adjustment=sum(rows,"adjustment");put(r,"adjustment",adjustment);
        BigDecimal gross=number(r,"sales_amount"),discount=number(r,"discount_total"),expected=null;
        if(gross!=null&&discount!=null&&adjustment!=null&&freight!=null)expected=gross.subtract(discount).add(adjustment).add(freight);
        put(r,"calculated_payable",expected);put(r,"payment_difference",expected==null||paid==null?null:paid.subtract(expected));
        BigDecimal goods=number(r,"goods_payment");put(r,"line_difference",goods==null||paid==null||freight==null?null:paid.subtract(goods.add(freight)));
        BigDecimal shares=sum(rows,"order_discount_share");put(r,"discount_share_difference",shares==null||orderDiscount==null?null:orderDiscount.subtract(shares));
        BigDecimal dividedDifference=divided==null||paid==null||freight==null?null:rows.size()==1&&divided.compareTo(paid)==0?BigDecimal.ZERO:paid.subtract(freight).subtract(divided);put(r,"platform_divided_difference",dividedDifference);
        boolean different=!differences.isEmpty();for(String key:Arrays.asList("payment_difference","line_difference","discount_share_difference","platform_divided_difference")){BigDecimal v=number(r,key);if(v!=null&&v.signum()!=0)different=true;}
        r.put("check_state",!issues.isEmpty()?"INVALID":different?"DIFFERENCE":expected==null||goods==null||shares==null?"INCOMPLETE":"MATCHED");
        r.put("issues",issues);r.put("differences",differences);r.put("lines",rows);
        List<Map<String,Object>> promotions=new ArrayList<>();JsonNode ps=trade.path("promotion_details").path("promotion_detail");if(!ps.isArray()&&!ps.isObject())ps=trade.path("promotion_details");List<JsonNode> details=new ArrayList<>();if(ps.isArray())ps.forEach(details::add);else if(ps.isObject())details.add(ps);
        for(JsonNode p:details){if(promotions.size()>=200)break;Map<String,Object> promotion=new LinkedHashMap<>();for(String key:Arrays.asList("id","promotion_id","promotion_name","promotion_desc"))promotion.put(key,p.path(key).asText(""));put(promotion,"amount",amount(p,"discount_fee",false,issues,"优惠明细"));promotions.add(promotion);}r.put("promotions",promotions);
        if(!issues.isEmpty())r.put("check_state","INVALID");return r;
    }
    public static Map<String,Object> fromPayload(String payload){try{return parse(JSON.readTree(payload).path("trade_fullinfo_get_response").path("trade"));}catch(Exception e){throw new IllegalArgumentException("订单交易资料无法解析",e);}}
    @SuppressWarnings("unchecked") public static Map<String,Object> stored(Object value){if(value instanceof Map)return new LinkedHashMap<>((Map<String,Object>)value);if(value==null)return new LinkedHashMap<>();try{return JSON.readValue(value.toString(),Map.class);}catch(Exception e){throw new IllegalArgumentException("订单交易资料快照无法读取",e);}}
    public static void attach(Map<String,Object> order){order.put("document",stored(order.remove("document_info")));}
    public static void related(OrderRepository repository,String company,Map<String,Object> order){
        order.put("internal_notes",repository.db().query("SELECT message,operator_name,create_time FROM oms_order_process_log WHERE company_code=? AND platform_order_id=? AND action='ORDER_NOTE' ORDER BY id DESC LIMIT 20",ROW,company,order.get("platform_order_id")));
        order.put("refund_amounts",repository.db().queryForMap("SELECT COALESCE(SUM(IF(funds_state='SUCCESS',refund_fee,0)),0) succeeded,COALESCE(SUM(IF(funds_state='NONE' AND state NOT IN ('CLOSED','CANCELED'),refund_fee,0)),0) pending FROM oms_refund WHERE company_code=? AND order_id=?",company,order.get("id")));
        // One local batch for all purchased sales rows. Gifts retain their own
        // workspace; a receipt count never implies an entire bag/set returned.
        order.put("purchase_returns",repository.db().query("SELECT p.id,p.refund_id,r.refund_sn,l.id order_line_id,l.platform_oid,p.sku_sn,p.quantity,p.accepted_quantity,p.posted_quantity,p.waived_quantity,p.state,IF(l.stocked_bundle_snapshot IS NULL,'件','套') stock_unit FROM oms_refund r JOIN oms_refund_return_plan p ON p.company_code=r.company_code AND p.refund_id=r.id JOIN oms_order_shipment_line sl ON sl.company_code=p.company_code AND sl.id=p.shipment_line_id JOIN oms_order_line l ON l.company_code=sl.company_code AND l.id=sl.order_line_id AND l.order_id=r.order_id WHERE r.company_code=? AND r.order_id=? AND p.gift_plan_id IS NULL AND l.gift_plan_id IS NULL ORDER BY p.id",ROW,company,order.get("id")));
    }
    public static void addNote(OrderRepository repository,String company,long order,String note,String actor){
        String value=note.trim();require(!value.isEmpty()&&value.length()<=500,"请填写1至500字内部备注");
        List<Map<String,Object>> rows=repository.db().query("SELECT platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,order);require(rows.size()==1,"业务订单不存在或无权访问");
        repository.log(company,OrderValues.number(rows.get(0),"platform_order_id"),"ORDER_NOTE","SUCCESS",value,actor);
    }
    static void latest(OrderRepository repository,String company,long platform,Long business,String payload){
        Map<String,Object> current=fromPayload(payload);repository.db().update("UPDATE oms_platform_order SET document_info=? WHERE company_code=? AND id=?",encode(current),company,platform);
        if(business!=null){Map<String,Object> adopted=stored(repository.db().queryForObject("SELECT document_info FROM oms_order WHERE company_code=? AND id=?",String.class,company,business));
            // Display metadata follows latest source. Adopted sales amounts and their source version stay frozen.
            for(String key:META)adopted.put(key,current.get(key));adopted.put("metadata_modified",current.get("source_modified"));repository.db().update("UPDATE oms_order SET document_info=? WHERE company_code=? AND id=?",encode(adopted),company,business);}
    }
}
