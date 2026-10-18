package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Company-scoped, read-only exchange projection; never changes sale totals or stock. */
final class OrderExchangeView {
    private OrderExchangeView() {}
    static void summaries(OrderRepository repository,String company,List<Map<String,Object>> orders){
        if(orders.isEmpty())return;
        List<Object> args=new ArrayList<>();args.add(company);for(Map<String,Object> o:orders)args.add(o.get("id"));
        Map<Long,Map<String,Object>> byOrder=new HashMap<>();
        String marks=String.join(",",Collections.nCopies(orders.size(),"?"));
        for(Map<String,Object> row:repository.db().query("SELECT order_id,COUNT(*) exchange_total_count,SUM(state NOT IN ('COMPLETED','CANCELED','REJECTED')) exchange_active_count,SUM(state='COMPLETED') exchange_completed_count,SUM(state IN ('CANCELED','REJECTED')) exchange_closed_count FROM oms_exchange WHERE company_code=? AND order_id IN ("+marks+") GROUP BY order_id",ROW,args.toArray()))byOrder.put(number(row,"order_id"),row);
        for(Map<String,Object> order:orders){for(String key:Arrays.asList("exchange_total_count","exchange_active_count","exchange_completed_count","exchange_closed_count"))order.put(key,0);order.putAll(byOrder.getOrDefault(number(order,"id"),Collections.emptyMap()));}
    }
    @SuppressWarnings("unchecked") static void detail(OrderRepository repository,OrderCatalog catalog,String company,Map<String,Object> order,List<Map<String,Object>> sale,List<Map<String,Object>> shipments){
        long id=number(order,"id");
        List<Map<String,Object>> all=repository.db().query("SELECT l.id,l.platform_oid,l.sku_sn,l.goods_name,l.quantity,l.cancelled_quantity,l.business_type,l.exchange_id,l.source_line_id,l.source_shipment_line_id,e.exchange_sn,e.source_phase exchange_phase FROM oms_order_line l LEFT JOIN oms_exchange e ON e.company_code=l.company_code AND e.id=l.exchange_id WHERE l.company_code=? AND l.order_id=? ORDER BY l.id",ROW,company,id);
        List<Map<String,Object>> cases=repository.db().query("SELECT id,exchange_sn,source_kind,source_phase,fulfillment_mode,linked_exchange_id,state,stage,platform_status,platform_operation,problem_codes,problem_message,last_error,platform_oid,order_line_id,source_shipment_line_id,fulfillment_line_id,original_sku,target_sku,target_description,requested_quantity,return_sn,return_qc,create_time FROM oms_exchange WHERE company_code=? AND order_id=? ORDER BY id DESC",ROW,company,id);
        Set<String> skus=new LinkedHashSet<>();for(Map<String,Object> l:all)if(!text(l,"sku_sn").isEmpty())skus.add(text(l,"sku_sn"));for(Map<String,Object> e:cases)for(String key:Arrays.asList("original_sku","target_sku"))if(!text(e,key).isEmpty())skus.add(text(e,key));
        Map<String,List<Map<String,Object>>> goods=new HashMap<>();
        if(!skus.isEmpty())try{List<String> requested=new ArrayList<>(skus);for(int offset=0;offset<requested.size();offset+=500)goods.putAll(catalog.goods(company,requested.subList(offset,Math.min(requested.size(),offset+500))));}catch(RuntimeException error){order.put("goods_error","当前 OMS 商品资料暂未加载，已保留商品快照与 SKU");order.put("exchange_goods_error","换货商品资料暂未加载，已保留商品快照与 SKU");}
        Map<Long,Map<String,Object>> byId=new LinkedHashMap<>();Set<Long> replaced=new HashSet<>();
        for(Map<String,Object> l:all){byId.put(number(l,"id"),l);if(number(l,"source_line_id")>0)replaced.add(number(l,"source_line_id"));List<Map<String,Object>> matches=goods.getOrDefault(text(l,"sku_sn"),Collections.emptyList());if(matches.size()==1){l.put("current_goods",matches.get(0));l.put("oms_goods_name",matches.get(0).get("goods_name"));}}
        Map<Long,List<Map<String,Object>>> shipping=new HashMap<>();
        for(Map<String,Object> s:repository.db().query("SELECT sl.order_line_id,sl.id shipment_line_id,sl.shipped_quantity,sl.quantity,s.id shipment_id,s.shipment_sn,s.status FROM oms_order_shipment_line sl JOIN oms_order_shipment s ON s.company_code=sl.company_code AND s.id=sl.shipment_id WHERE s.company_code=? AND s.order_id=? ORDER BY s.id,sl.id",ROW,company,id))shipping.computeIfAbsent(number(s,"order_line_id"),k->new ArrayList<>()).add(s);
        List<Map<String,Object>> after=new ArrayList<>();
        for(Map<String,Object> l:all){
            long lineId=number(l,"id"),parent=number(l,"source_line_id");List<Map<String,Object>> chain=new ArrayList<>();Set<Long> seen=new HashSet<>();Map<String,Object> node=l;
            while(node!=null&&seen.add(number(node,"id"))){Map<String,Object> step=new LinkedHashMap<>(node);step.remove("lineage");step.put("shipments",shipping.getOrDefault(number(node,"id"),Collections.emptyList()));chain.add(step);long source=number(node,"source_line_id");node=source>0?byId.get(source):null;if(source>0&&node==null)l.put("lineage_incomplete",true);}
            if(node!=null)l.put("lineage_incomplete",true);Collections.reverse(chain);l.put("lineage",chain);l.put("exchange_generation",Math.max(0,chain.size()-1));
            if(parent>0&&byId.containsKey(parent)){Map<String,Object> source=byId.get(parent);l.put("source_product",ExchangeService.map("id",source.get("id"),"sku_sn",source.get("sku_sn"),"goods_name",source.get("goods_name"),"current_goods",source.get("current_goods")));}
            l.put("was_replaced",replaced.contains(lineId));l.put("shipments",shipping.getOrDefault(lineId,Collections.emptyList()));
            long shipped=shipping.getOrDefault(lineId,Collections.emptyList()).stream().mapToLong(s->number(s,"shipped_quantity")).sum();l.put("shipped_quantity",shipped);
            if("EXCHANGE".equals(text(l,"business_type")))after.add(l);
        }
        for(Map<String,Object> l:sale){Map<String,Object> meta=byId.get(number(l,"id"));if(meta!=null)for(String key:Arrays.asList("oms_goods_name","current_goods","lineage","exchange_generation","source_product","was_replaced","shipments","lineage_incomplete"))if(meta.containsKey(key))l.put(key,meta.get(key));}
        for(Map<String,Object> e:cases){for(String key:Arrays.asList("original","target")){List<Map<String,Object>> matches=goods.getOrDefault(text(e,key+"_sku"),Collections.emptyList());if(matches.size()==1)e.put(key+"_goods",matches.get(0));}List<Map<String,Object>> owned=new ArrayList<>();for(Map<String,Object> s:shipments)if(number(s,"exchange_id")==number(e,"id"))owned.add(s);e.put("shipments",owned);}
        order.put("after_sales_lines",after);order.put("exchange_records",cases);
    }
}
