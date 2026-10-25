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
    static void requireShipmentReady(OrderRepository r,String company,Map<String,Object> shipment){
        List<String> blocked=r.db().queryForList("SELECT ol.refund_block_reason FROM oms_order_shipment_line sl JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE sl.company_code=? AND sl.shipment_id=? AND sl.quantity>sl.shipped_quantity+sl.cancelled_quantity AND ol.refund_block_reason<>'' ORDER BY sl.id LIMIT 1",String.class,company,shipment.get("id"));require(blocked.isEmpty(),blocked.isEmpty()?"":blocked.get(0));
    }
    private static boolean knownScopes(OrderRepository r,String company,Map<String,Object> order,String payload){
        try{JsonNode trade=JSON.readTree(payload).path("trade_fullinfo_get_response").path("trade");List<JsonNode> nodes=new ArrayList<>();nodes.add(trade);for(JsonNode n:trade.path("orders").path("order"))nodes.add(n);boolean seen=false;
            for(JsonNode n:nodes){String status=n.path("refund_status").asText("");if(status.isEmpty()||Arrays.asList("NO_REFUND","CLOSED").contains(status))continue;seen=true;String id=n.path("refund_id").asText("");if(!id.matches("[1-9][0-9]{0,19}"))return false;List<String> oids=r.db().queryForList("SELECT p.platform_oid FROM oms_platform_refund p WHERE p.company_code=? AND p.channel_id=? AND p.platform_tid=? AND p.refund_id=? AND p.source_state='VALID' AND EXISTS(SELECT 1 FROM oms_order_line l WHERE l.company_code=p.company_code AND l.order_id=? AND l.platform_oid=p.platform_oid)",String.class,company,order.get("channel_id"),order.get("platform_tid"),id,order.get("business_order_id"));if(oids.size()!=1)return false;}return seen;
        }catch(RuntimeException e){return false;}catch(Exception e){return false;}
    }
    static void refresh(OrderRepository r,String company,long channel,String tid){
        List<Map<String,Object>> orders=r.db().query("SELECT * FROM oms_platform_order WHERE company_code=? AND channel_id=? AND platform_tid=? FOR UPDATE",ROW,company,channel,tid);if(orders.isEmpty())return;
        List<Map<String,Object>> cases=r.db().query("SELECT p.refund_id,p.platform_oid,p.platform_status,p.source_state,p.version,b.id business_id,b.approved_version,b.review_state,b.goods_action,b.goods_state FROM oms_platform_refund p LEFT JOIN oms_refund b ON b.company_code=p.company_code AND b.platform_refund_id=p.id WHERE p.company_code=? AND p.channel_id=? AND p.platform_tid=? ORDER BY p.id",ROW,company,channel,tid);
        for(Map<String,Object> order:orders){String reason="",fallback="";Object business=order.get("business_order_id");
            if(business!=null)r.db().update("UPDATE oms_order_line SET refund_block_reason='' WHERE company_code=? AND order_id=? AND refund_block_reason<>''",company,business);
            for(Map<String,Object> c:cases){boolean pending=(!"CLOSED".equals(text(c,"platform_status"))||!"VALID".equals(text(c,"source_state"))||c.get("business_id")!=null&&!Arrays.asList("WAIT_REVIEW","CANCELED").contains(text(c,"goods_state")))&&!handled(c);if(!pending)continue;
                String message="平台退款 "+text(c,"refund_id")+" 的本子单商品待审核或货物处理";long matched=business==null?0:r.db().queryForObject("SELECT COUNT(*) FROM oms_order_line WHERE company_code=? AND order_id=? AND platform_oid=?",Long.class,company,business,c.get("platform_oid"));if(matched==0){fallback=message;continue;}
                if("MIXED".equals(text(c,"goods_action"))&&"VALID".equals(text(c,"source_state"))&&"APPROVED".equals(text(c,"review_state"))&&number(c,"approved_version")==number(c,"version")){
                    r.db().update("UPDATE oms_order_line l SET refund_block_reason=? WHERE l.company_code=? AND l.order_id=? AND l.platform_oid=? AND EXISTS(SELECT 1 FROM oms_refund_goods_task t WHERE t.company_code=l.company_code AND t.order_line_id=l.id AND t.refund_id=? AND t.action='CANCEL' AND t.state NOT IN ('DONE','CANCELED'))",message,company,business,c.get("platform_oid"),c.get("business_id"));
                }else r.db().update("UPDATE oms_order_line SET refund_block_reason=? WHERE company_code=? AND order_id=? AND platform_oid=?",message,company,business,c.get("platform_oid"));
                reason=message;
            }
            if(!fallback.isEmpty())reason=fallback;
            else if(business!=null){long free=r.db().queryForObject("SELECT COUNT(*) FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type='SALE' AND gift_plan_id IS NULL AND exchange_id IS NULL AND quantity>cancelled_quantity AND refund_block_reason=''",Long.class,company,business);if(free>0)reason="";}
            boolean changed=!reason.equals(text(order,"refund_block_reason"));r.db().update("UPDATE oms_platform_order SET refund_block_reason=? WHERE company_code=? AND id=?",reason,company,order.get("id"));if(business!=null)r.db().update("UPDATE oms_order SET refund_block_reason=? WHERE company_code=? AND id=?",reason,company,business);
            if((covers(r,company,order,text(order,"raw_payload"))||knownScopes(r,company,order,text(order,"raw_payload")))&&text(order,"fulfillment_block_reason").contains("退款")){r.db().update("UPDATE oms_platform_order SET fulfillment_block_reason=? WHERE company_code=? AND id=?",new TmallOrderParser().parse(text(order,"raw_payload")).hold?"平台订单暂缓发货":"",company,order.get("id"));changed=true;}
            if(changed&&business!=null)new OrderUpdateService(r).refreshProjection(company,number(order,"id"));
        }
    }
}
