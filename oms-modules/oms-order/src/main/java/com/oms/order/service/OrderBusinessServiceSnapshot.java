package com.oms.order.service;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
final class OrderBusinessServiceSnapshot {
 private final OrderRepository repository;
 OrderBusinessServiceSnapshot(OrderRepository r){repository=r;}
 Map<String,Object> get(String company,long id){List<Map<String,Object>> rows=repository.db().query("SELECT o.order_sn,(SELECT COALESCE(SUM(l.shipped_quantity),0) FROM oms_order_shipment s JOIN oms_order_shipment_line l ON l.company_code=s.company_code AND l.shipment_id=s.id WHERE s.company_code=o.company_code AND s.order_id=o.id AND s.business_type='SALE') shipped,(SELECT COUNT(*) FROM oms_order_shipment s WHERE s.company_code=o.company_code AND s.order_id=o.id AND s.business_type='SALE' AND (EXISTS(SELECT 1 FROM oms_order_shipment_dispatch d WHERE d.company_code=s.company_code AND d.shipment_id=s.id) OR EXISTS(SELECT 1 FROM oms_order_shipment_virtual_outbound v WHERE v.company_code=s.company_code AND v.shipment_id=s.id))) warehouse_tasks FROM oms_order o WHERE o.company_code=? AND o.id=?",ROW,company,id);require(rows.size()==1,"业务订单不存在或无权访问");return rows.get(0);}
}
