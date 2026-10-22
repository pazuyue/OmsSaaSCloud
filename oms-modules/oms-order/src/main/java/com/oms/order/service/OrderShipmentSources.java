package com.oms.order.service;

import java.util.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import static com.oms.order.service.OrderValues.*;

/** Physical documents can contain several immutable inventory references. */
final class OrderShipmentSources {
    private OrderShipmentSources() {}
    static List<Map<String,Object>> lines(OrderRepository repository,String company,long shipment,boolean lock) {
        return repository.db().query("SELECT sl.*,ol.gift_plan_id,COALESCE(NULLIF(ol.stock_reference,''),NULLIF(s.stock_reference,''),s.platform_tid) stock_reference FROM oms_order_shipment_line sl JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id JOIN oms_order_shipment s ON s.company_code=sl.company_code AND s.id=sl.shipment_id WHERE sl.company_code=? AND sl.shipment_id=? ORDER BY sl.id"+(lock?" FOR UPDATE":""),ROW,company,shipment);
    }
    static List<Map<String,Object>> receipts(OrderWarehouseClient inventory,String company,Map<String,Object> shipment,List<Map<String,Object>> lines) {
        Set<String> references=new TreeSet<>();for(Map<String,Object> line:lines)references.add(text(line,"stock_reference"));
        require(!references.isEmpty(),"发货单库存来源缺失");
        if(references.size()==1)return data(inventory.receipts(company,number(shipment,"channel_id"),references.iterator().next(),SecurityConstants.INNER));
        Map<String,Object> body=new LinkedHashMap<>();body.put("channel_id",shipment.get("channel_id"));body.put("references",references);
        return data(inventory.receiptsBatch(company,body,SecurityConstants.INNER));
    }
    static String key(String reference,Map<String,Object> line) {return reference+"/"+com.oms.common.util.FulfillmentLineKey.of(line);}
}
