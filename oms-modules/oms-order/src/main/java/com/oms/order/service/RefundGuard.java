package com.oms.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Materialized fulfillment gate exists before conversion and survives ordinary manual resume. */
final class RefundGuard {
    private RefundGuard() {}
    static boolean handled(Map<String,Object> row){return "VALID".equals(text(row,"source_state"))&&number(row,"approved_version")==number(row,"version")&&( "APPROVED".equals(text(row,"review_state"))||"CLOSED".equals(text(row,"platform_status"))&&"CLOSED".equals(text(row,"review_state"))&&"DONE".equals(text(row,"goods_state")))&&("KEEP".equals(text(row,"goods_action"))||"DONE".equals(text(row,"goods_state")));}
    static boolean covers(OrderRepository r,String company,Map<String,Object> order,String payload){
        try{JsonNode trade=JSON.readTree(payload).path("trade_fullinfo_get_response").path("trade");List<JsonNode> nodes=new ArrayList<>();nodes.add(trade);for(JsonNode n:trade.path("orders").path("order"))nodes.add(n);boolean seen=false;
            for(JsonNode n:nodes){String status=n.path("refund_status").asText("");if(status.isEmpty()||Arrays.asList("NO_REFUND","CLOSED").contains(status))continue;seen=true;String id=n.path("refund_id").asText("");if(!id.matches("[1-9][0-9]{0,19}"))return false;List<Map<String,Object>> rows=r.db().query("SELECT p.version,p.platform_status,p.source_state,b.approved_version,b.review_state,b.goods_action,b.goods_state FROM oms_platform_refund p JOIN oms_refund b ON b.company_code=p.company_code AND b.platform_refund_id=p.id WHERE p.company_code=? AND p.channel_id=? AND p.platform_tid=? AND p.refund_id=? AND p.platform_status=?",ROW,company,order.get("channel_id"),order.get("platform_tid"),id,status);if(rows.size()!=1||!handled(rows.get(0)))return false;}
            return seen;
        }catch(Exception e){return false;}
    }
    static void refresh(OrderRepository r,String company,long channel,String tid){
        List<Map<String,Object>> orders=r.db().query("SELECT * FROM oms_platform_order WHERE company_code=? AND channel_id=? AND platform_tid=? FOR UPDATE",ROW,company,channel,tid);if(orders.isEmpty())return;
        List<Map<String,Object>> cases=r.db().query("SELECT p.refund_id,p.platform_status,p.source_state,p.version,b.id business_id,b.approved_version,b.review_state,b.goods_action,b.goods_state FROM oms_platform_refund p LEFT JOIN oms_refund b ON b.company_code=p.company_code AND b.platform_refund_id=p.id WHERE p.company_code=? AND p.channel_id=? AND p.platform_tid=? ORDER BY p.id",ROW,company,channel,tid);
        String reason="";for(Map<String,Object> c:cases)if((!"CLOSED".equals(text(c,"platform_status"))||!"VALID".equals(text(c,"source_state"))||c.get("business_id")!=null&&!Arrays.asList("WAIT_REVIEW","CANCELED").contains(text(c,"goods_state")))&&!handled(c)){reason="平台退款 "+text(c,"refund_id")+" 待转单、审核或货物处理，请到退款管理核对";break;}
        for(Map<String,Object> order:orders){boolean changed=!reason.equals(text(order,"refund_block_reason"));r.db().update("UPDATE oms_platform_order SET refund_block_reason=? WHERE company_code=? AND id=?",reason,company,order.get("id"));if(order.get("business_order_id")!=null)r.db().update("UPDATE oms_order SET refund_block_reason=? WHERE company_code=? AND id=?",reason,company,order.get("business_order_id"));
            if(covers(r,company,order,text(order,"raw_payload"))&&text(order,"fulfillment_block_reason").contains("退款")){r.db().update("UPDATE oms_platform_order SET fulfillment_block_reason=? WHERE company_code=? AND id=?",new TmallOrderParser().parse(text(order,"raw_payload")).hold?"平台订单暂缓发货":"",company,order.get("id"));changed=true;}
            if(changed&&order.get("business_order_id")!=null)new OrderUpdateService(r).refreshProjection(company,number(order,"id"));
        }
    }
}
