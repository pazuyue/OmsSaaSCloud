package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** A platform suborder may have several immutable fulfillment lines, including A -> B -> A. */
final class OrderFulfillmentLines {
    private OrderFulfillmentLines() {}
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
