package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** A platform suborder may have several immutable fulfillment lines, including A -> B -> A. */
final class OrderFulfillmentLines {
    private OrderFulfillmentLines() {}
    /** Current reads after the parent lock; an earlier consistent-read snapshot cannot authorize new fulfillment. */
    static Map<String,Object> balance(OrderRepository r,String company,long order) {
        long quantity=0,cancelled=0,reserved=0,shipped=0,pending=0;
        for(Map<String,Object> line:r.db().query("SELECT quantity,cancelled_quantity,reserved_quantity FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type='SALE' ORDER BY id FOR UPDATE",ROW,company,order)){
            quantity+=number(line,"quantity");cancelled+=number(line,"cancelled_quantity");reserved+=number(line,"reserved_quantity");
        }
        for(Map<String,Object> line:r.db().query("SELECT sl.quantity,sl.cancelled_quantity,sl.shipped_quantity,sh.status FROM oms_order_shipment_line sl JOIN oms_order_shipment sh ON sh.company_code=sl.company_code AND sh.id=sl.shipment_id WHERE sh.company_code=? AND sh.order_id=? AND sh.business_type='SALE' ORDER BY sh.id,sl.id FOR UPDATE",ROW,company,order)){
            shipped+=number(line,"shipped_quantity");if(!Arrays.asList("VOIDED","CLOSED").contains(text(line,"status")))pending+=Math.max(0,number(line,"quantity")-number(line,"cancelled_quantity")-number(line,"shipped_quantity"));
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("quantity",quantity);result.put("cancelled",cancelled);result.put("reserved",reserved);result.put("shipped",shipped);result.put("pending",pending);return result;
    }
    static List<Map<String,Object>> sale(OrderRepository r,String company,long order) {
        return r.db().query("SELECT * FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type='SALE' ORDER BY id",ROW,company,order);
    }
    static String reference(Map<String,Object> order,Map<String,Object> line) {
        return text(line,"stock_reference").isEmpty()?stockReference(order):text(line,"stock_reference");
    }
    static Map<String,List<Map<String,Object>>> groups(Map<String,Object> order,List<Map<String,Object>> lines) {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(Map<String,Object> line:lines)result.computeIfAbsent(reference(order,line),k->new ArrayList<>()).add(line);
        return result;
    }
    static Map<String,Object> context(Map<String,Object> order,String reference) {
        Map<String,Object> result=new LinkedHashMap<>(order);if(!reference.equals(stockReference(order)))result.put("stock_reference",reference);return result;
    }
    static long required(List<Map<String,Object>> lines) {
        return lines.stream().mapToLong(l->number(l,"quantity")-number(l,"cancelled_quantity")).sum();
    }
    static boolean revised(OrderRepository r,String company,long order) {
        return r.db().queryForObject("SELECT COUNT(*) FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type='SALE' AND exchange_id IS NOT NULL",Long.class,company,order)>0;
    }
}
