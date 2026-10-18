package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.map;

/** A source-side gate protects affected unshipped goods even before a HH can be created. */
final class PlatformExchangeGuard {
    private PlatformExchangeGuard() {}
    static void refresh(OrderRepository r,String company,long channel,String oid){
        if(oid.isEmpty())return;
        for(Map<String,Object> order:r.db().query("SELECT DISTINCT o.id,o.platform_order_id FROM oms_order o JOIN oms_order_line l ON l.company_code=o.company_code AND l.order_id=o.id WHERE o.company_code=? AND o.channel_id=? AND l.platform_oid=? AND o.business_type='SALE' ORDER BY o.id",ROW,company,channel,oid))refreshOrder(r,company,number(order,"id"));
    }
    static void refreshOrder(OrderRepository r,String company,long id){
        List<Map<String,Object>> orders=r.db().query("SELECT id,platform_order_id,exchange_block_reason FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id);if(orders.isEmpty())return;
        List<Map<String,Object>> sources=r.db().query("SELECT DISTINCT p.id,p.dispute_id,p.source_state,p.platform_status,b.state business_state,b.fulfillment_mode,b.fulfillment_line_id FROM oms_platform_exchange p JOIN oms_order o ON o.company_code=p.company_code AND o.channel_id=p.channel_id JOIN oms_order_line l ON l.company_code=o.company_code AND l.order_id=o.id AND l.platform_oid=p.platform_oid LEFT JOIN oms_exchange b ON b.company_code=p.company_code AND b.id=p.business_exchange_id WHERE o.company_code=? AND o.id=? AND l.business_type='SALE' AND l.quantity-l.cancelled_quantity>COALESCE((SELECT SUM(sl.shipped_quantity) FROM oms_order_shipment_line sl WHERE sl.company_code=l.company_code AND sl.order_line_id=l.id),0) ORDER BY p.id",ROW,company,id);
        String reason="";Map<String,Object> evidence=map("order_id",id);
        for(Map<String,Object> p:sources){boolean valid="VALID".equals(text(p,"source_state"));boolean handled=valid&&((Arrays.asList("4","5","14").contains(text(p,"platform_status"))&&text(p,"business_state").isEmpty())||Arrays.asList("COMPLETED","CANCELED","CLOSED","REJECTED").contains(text(p,"business_state"))||"UNSHIPPED".equals(text(p,"fulfillment_mode"))&&p.get("fulfillment_line_id")!=null&&!"BLOCKED".equals(text(p,"business_state")));if(!handled){reason="平台换货 "+text(p,"dispute_id")+" 涉及未发商品，待关联、转单或方案核对";evidence.put("platform_exchange_id",p.get("id"));evidence.put("dispute_id",p.get("dispute_id"));break;}}
        r.db().update("UPDATE oms_order SET exchange_block_reason=? WHERE company_code=? AND id=?",reason,company,id);r.db().update("UPDATE oms_platform_order SET exchange_block_reason=? WHERE company_code=? AND id=?",reason,company,orders.get(0).get("platform_order_id"));
        List<Map<String,Object>> desired=new ArrayList<>();if(!reason.isEmpty())desired.add(map("domain","PLATFORM_EXCHANGE","category","AFTER_SALE","reason_code","PLATFORM_EXCHANGE_PENDING","scope","ORDER","stage","REVIEW","sku_sn","","severity","WARNING","blocking",1,"message",reason,"evidence",evidence));new OrderIssueService(r).reconcileDomain(company,id,"PLATFORM_EXCHANGE",desired);
    }
}
