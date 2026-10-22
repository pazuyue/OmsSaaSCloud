package com.oms.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Reads the immutable order payload, never the current listing's outer SKU. */
public class TmallOrderParser {
    public static class Trade {
        public String tid,status,type,seller,name,phone,address,oaid,fingerprint;
        public boolean hold;
        public String fulfillmentBlock="";
        public BigDecimal payment;
        public Map<String,Object> document;
        public LocalDateTime created,modified;
        public List<Line> lines=new ArrayList<>();
        public boolean eligible(Collection<String> allowedTypes){return "WAIT_SELLER_SEND_GOODS".equals(status)&&allowedTypes.contains(type);}
        public String ineligibleReason(Collection<String> allowedTypes){
            if(!"WAIT_SELLER_SEND_GOODS".equals(status))return "订单状态 "+(status.isEmpty()?"（缺失）":status)+" 不满足转单条件，需为 WAIT_SELLER_SEND_GOODS（已付款待发货）";
            return "交易类型 "+(type.isEmpty()?"（缺失）":type)+" 未在店铺允许转单类型中，请到转单设置中配置";
        }
    }
    public static class Line {
        public String oid,itemId,skuId,outerSku,outerIid,title,spec;
        public int quantity;
        public BigDecimal payment;
    }
    private boolean refundBlocked(String status){return !status.isEmpty()&&!Arrays.asList("NO_REFUND","CLOSED").contains(status);}
    private String str(JsonNode node,String key){JsonNode value=node.get(key);return value==null||value.isNull()?"":value.asText();}
    // Invalid amounts block conversion, but must not prevent known-SKU demand registration.
    private BigDecimal money(JsonNode node,String key){String value=str(node,key);if(value.isEmpty())return null;try{BigDecimal number=new BigDecimal(value);return number.signum()>=0&&number.precision()<=18&&number.scale()<=2?number:null;}catch(NumberFormatException e){return null;}}
    private LocalDateTime date(JsonNode node,String key){String value=str(node,key);return value.isEmpty()?null:time(value);}
    public Trade parse(String payload) {
        try {
            require(payload!=null&&payload.length()<=2000000,"订单报文为空或超过2MB");
            JsonNode root=JSON.readTree(payload),trade=root.path("trade_fullinfo_get_response").path("trade");
            require(trade.isObject(),"报文缺少trade_fullinfo_get_response.trade");
            Trade t=new Trade();t.tid=str(trade,"tid");require(t.tid.matches("[0-9]{1,64}"),"平台订单号无效");
            t.status=str(trade,"status");t.type=str(trade,"type");t.seller=str(trade,"seller_nick");t.payment=money(trade,"payment");
            require(!t.status.isEmpty(),"报文缺少订单 status 状态");t.created=date(trade,"created");t.modified=date(trade,"modified");t.name=str(trade,"receiver_name");t.phone=str(trade,"receiver_mobile");if(t.phone.isEmpty())t.phone=str(trade,"receiver_phone");
            t.address=String.join(" ",str(trade,"receiver_state"),str(trade,"receiver_city"),str(trade,"receiver_district"),str(trade,"receiver_town"),str(trade,"receiver_address")).trim();
            t.oaid=str(trade,"oaid");t.hold="1".equals(str(trade,"top_hold"));
            if(t.hold)t.fulfillmentBlock="平台订单暂缓发货";
            if(refundBlocked(str(trade,"refund_status")))t.fulfillmentBlock="平台订单存在退款，等待订单更新处理";
            JsonNode children=trade.path("orders").path("order");List<JsonNode> items=new ArrayList<>();
            if(children.isArray())children.forEach(items::add);else if(children.isObject())items.add(children);
            require(!items.isEmpty()&&items.size()<=500,"订单明细为空或超过500行");Set<String> ids=new HashSet<>();
            for(JsonNode row:items){if(refundBlocked(str(row,"refund_status")))t.fulfillmentBlock="平台子订单存在退款，等待订单更新处理";Line line=new Line();line.oid=str(row,"oid");require(line.oid.matches("[0-9]{1,64}")&&ids.add(line.oid),"子订单号无效或重复");line.itemId=str(row,"num_iid");line.skuId=str(row,"sku_id");line.outerSku=str(row,"outer_sku_id");line.outerIid=str(row,"outer_iid");line.title=str(row,"title");line.spec=str(row,"sku_properties_name");require(line.spec.length()<=1000,"平台商品规格超过1000字");
                try{line.quantity=new BigDecimal(str(row,"num")).intValueExact();}catch(ArithmeticException|NumberFormatException e){throw new ConversionException("ORDER_DATA_INVALID","子订单 "+line.oid+" 的 num 数量必须为正整数");}require(line.quantity>0&&line.quantity<=100000000,"子订单 "+line.oid+" 的 num 数量须为1至100000000");
                line.payment=money(row,str(row,"divide_order_fee").isEmpty()?"payment":"divide_order_fee");t.lines.add(line);
            }
            // Only transaction/stock-relevant fields pause processing. Titles, logistics
            // display fields, JSON key order and child order do not alter the snapshot.
            ObjectNode semantic=JSON.createObjectNode();for(String key:Arrays.asList("tid","seller_nick","status","type","payment","refund_status","refund_id","receiver_name","receiver_mobile","receiver_phone","receiver_state","receiver_city","receiver_district","receiver_town","receiver_address","oaid","top_hold"))semantic.put(key,str(trade,key));
            com.fasterxml.jackson.databind.node.ArrayNode fingerprints=semantic.putArray("orders");
            items.sort(Comparator.comparing(row->str(row,"oid")));
            for(JsonNode row:items){ObjectNode item=fingerprints.addObject();for(String key:Arrays.asList("oid","num_iid","sku_id","outer_sku_id","outer_iid","num","payment","divide_order_fee","status","refund_status","refund_id"))item.put(key,str(row,key));}
            for(String key:OrderDocumentInfo.TRADE_MONEY)semantic.set(key,trade.path(key));
            for(int i=0;i<items.size();i++)for(String key:OrderDocumentInfo.LINE_MONEY)((ObjectNode)fingerprints.get(i)).set(key,items.get(i).path(key));
            t.document=OrderDocumentInfo.parse(trade);
            @SuppressWarnings("unchecked") List<Map<String,Object>> commercial=(List<Map<String,Object>>)t.document.get("lines");
            Map<String,Map<String,Object>> commercialByOid=new HashMap<>();for(Map<String,Object> row:commercial)commercialByOid.put(text(row,"platform_oid"),row);
            for(Line line:t.lines){Map<String,Object> row=commercialByOid.get(line.oid);Object goods=row.get("goods_payment");boolean confirmed=text(row,"payment_source").startsWith("??")||"MATCHED".equals(t.document.get("check_state"));if(line.payment!=null&&goods!=null&&confirmed)line.payment=new BigDecimal(goods.toString());}
            t.fingerprint=hash(semantic.toString());return t;
        } catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("天猫订单报文无法解析，请核对原始数据",e);}
    }
    public List<Map<String,Object>> conversionIssues(Trade trade) {
        List<Map<String,Object>> issues=new ArrayList<>();
        if(trade.hold)issue(issues,"PLATFORM_HOLD","top_hold","","","平台订单暂缓发货");
        if(!trade.fulfillmentBlock.isEmpty()&&!trade.hold)issue(issues,"PLATFORM_REFUND","refund_status","","",trade.fulfillmentBlock);
        if(trade.payment==null)issue(issues,"AMOUNT_INVALID","payment","","","订单实付金额缺失或无效");
        if(trade.oaid.isEmpty()&&(trade.name.isEmpty()||trade.address.isEmpty()||trade.phone.isEmpty()))issue(issues,"RECIPIENT_MISSING","receiver / oaid","","","收货信息不完整且缺少平台OAID");
        for(Line line:trade.lines)if(line.payment==null)issue(issues,"AMOUNT_INVALID","divide_order_fee / payment",line.oid,line.outerSku,"子订单分摊实付金额缺失或无效");
        return issues;
    }
    private void issue(List<Map<String,Object>> issues,String code,String field,String oid,String sku,String message){Map<String,Object> issue=new LinkedHashMap<>();issue.put("code",code);issue.put("field",field);issue.put("platform_oid",oid);issue.put("outer_sku_id",sku);issue.put("message",message);issues.add(issue);}
    public void validateConversion(Trade trade,Collection<String> allowedTypes) {
        require(trade.eligible(allowedTypes),trade.ineligibleReason(allowedTypes));ConversionException.check(!trade.hold,"PLATFORM_HOLD","平台订单暂缓发货，等待平台解除后处理");
        ConversionException.check(trade.payment!=null,"AMOUNT_INVALID","缺少订单实付金额");
        ConversionException.check(!trade.oaid.isEmpty()||(!trade.name.isEmpty()&&!trade.address.isEmpty()&&!trade.phone.isEmpty()),"RECIPIENT_MISSING","缺少收货信息或平台OAID");
        for(Line line:trade.lines)ConversionException.check(line.payment!=null,"AMOUNT_INVALID","子订单缺少分摊实付金额："+line.oid);
    }
}
