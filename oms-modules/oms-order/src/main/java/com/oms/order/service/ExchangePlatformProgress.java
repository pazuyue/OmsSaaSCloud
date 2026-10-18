package com.oms.order.service;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Read-only platform progress, batched independently of logs and warehouse commands. */
final class ExchangePlatformProgress {
    private ExchangePlatformProgress() {}
    static void enrich(JdbcTemplate db,String company,List<Map<String,Object>> exchanges) {
        if(exchanges.isEmpty())return;
        require(exchanges.size()<=100,"单次换货进度查询最多100张单据");
        List<Object> args=new ArrayList<>();args.add(company);
        for(Map<String,Object> e:exchanges){args.add(e.get("id"));e.put("platform_shipped_quantity",0);e.put("platform_packaged_quantity",0);e.put("platform_tracking_count",0);}
        String ids=String.join(",",Collections.nCopies(exchanges.size(),"?"));
        Map<Long,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> e:exchanges)byId.put(number(e,"id"),e);
        for(Map<String,Object> r:db.query("SELECT s.exchange_id,COALESCE(SUM(l.shipped_quantity),0) qty FROM oms_order_shipment s JOIN oms_order_shipment_line l ON l.company_code=s.company_code AND l.shipment_id=s.id WHERE s.company_code=? AND s.business_type='EXCHANGE' AND s.exchange_id IN ("+ids+") GROUP BY s.exchange_id",ROW,args.toArray()))byId.get(number(r,"exchange_id")).put("platform_shipped_quantity",r.get("qty"));
        for(Map<String,Object> r:db.query("SELECT s.exchange_id,COALESCE(SUM(p.quantity),0) qty,COUNT(DISTINCT p.carrier_code,p.express_code) tracks FROM oms_order_shipment s JOIN oms_order_shipment_package p ON p.company_code=s.company_code AND p.shipment_id=s.id WHERE s.company_code=? AND s.business_type='EXCHANGE' AND s.exchange_id IN ("+ids+") GROUP BY s.exchange_id",ROW,args.toArray())){Map<String,Object> e=byId.get(number(r,"exchange_id"));e.put("platform_packaged_quantity",r.get("qty"));e.put("platform_tracking_count",r.get("tracks"));}
        for(Map<String,Object> r:db.query("SELECT e.id,JSON_UNQUOTE(JSON_EXTRACT(e.platform_request,'$.operation')) platform_action,COALESCE(c.write_enabled,0) platform_write_enabled FROM oms_exchange e LEFT JOIN oms_exchange_config c ON c.company_code=e.company_code AND c.channel_id=e.channel_id WHERE e.company_code=? AND e.id IN ("+ids+")",ROW,args.toArray())){Map<String,Object> e=byId.get(number(r,"id"));e.put("platform_action",r.get("platform_action"));e.put("platform_write_enabled",r.get("platform_write_enabled"));}
        for(Map<String,Object> e:exchanges)if(e!=byId.get(number(e,"id")))for(String field:Arrays.asList("platform_shipped_quantity","platform_packaged_quantity","platform_tracking_count","platform_action","platform_write_enabled"))e.put(field,byId.get(number(e,"id")).get(field));
    }
}
