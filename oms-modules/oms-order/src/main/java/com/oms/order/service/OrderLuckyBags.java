package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Sales bag quantities remain distinct from variable physical component quantities. */
final class OrderLuckyBags {
    private OrderLuckyBags(){}
    static boolean lucky(Map<String,Object> snapshot){return "LUCKY_BAG".equals(text(snapshot,"business_type"));}
    static Map<String,Object> definition(String sku,String name,Map<String,Object> goods){Map<String,Object> rule=OrderBundles.object(goods.get("lucky_bag"));require(number(rule,"revision")>0,"福袋规则尚未发布："+sku);Map<String,Object> snapshot=new LinkedHashMap<>(rule);snapshot.put("business_type","LUCKY_BAG");snapshot.put("inventory_mode","VIRTUAL");snapshot.put("sku_sn",sku);snapshot.put("goods_name",name);return snapshot;}
    static List<Map<String,Object>> expand(Map<String,Object> source,Map<String,Object> snapshot){require(snapshot.get("units") instanceof List&&snapshot.get("components") instanceof List,"福袋实际组成尚未确定，不能生成实物履约明细");require(number(source,"quantity")==number(snapshot,"quantity"),"福袋销售袋数与原库存快照不一致");List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> c:com.oms.common.lucky.LuckyBagRules.rows(snapshot.get("components"))){Map<String,Object> line=new LinkedHashMap<>(source);line.put("sku_sn",c.get("sku_sn"));line.put("goods_name",c.get("goods_name"));line.put("quantity",c.get("quantity"));line.put("component_quantity",0);line.put("bundle_quantity",source.get("quantity"));line.put("bundle_sku",snapshot.get("sku_sn"));line.put("bundle_name",snapshot.get("goods_name"));line.put("bundle_payment",source.get("payment"));line.put("payment",java.math.BigDecimal.ZERO);result.add(line);}return result;}
    /** Complete bags are calculated from their immutable contents, never a fixed component ratio. */
    static long complete(Map<String,Object> snapshot,Map<String,Long> quantities){Map<String,Long> left=new HashMap<>(quantities);long complete=0;for(Map<String,Object> unit:com.oms.common.lucky.LuckyBagRules.rows(snapshot.get("units"))){Map<String,Long> needed=new HashMap<>();for(Map<String,Object> c:com.oms.common.lucky.LuckyBagRules.rows(unit.get("components")))needed.merge(text(c,"sku_sn"),number(c,"quantity"),Long::sum);if(needed.entrySet().stream().allMatch(e->left.getOrDefault(e.getKey(),0L)>=e.getValue())){complete++;for(Map.Entry<String,Long> e:needed.entrySet())left.compute(e.getKey(),(k,v)->v-e.getValue());}}return complete;}
    /** Match the original warehouse/batch reservation source, including repeated SKUs. */
    static long completeSources(Map<String,Object> snapshot,Map<Long,Long> quantities){
        Map<Long,Long> left=new HashMap<>(quantities);long count=0;
        for(Map<String,Object> unit:com.oms.common.lucky.LuckyBagRules.rows(snapshot.get("units"))){
            Map<Long,Long> needed=new HashMap<>();for(Map<String,Object> c:com.oms.common.lucky.LuckyBagRules.rows(unit.get("components")))needed.merge(number(c,"stock_source_id"),Math.max(1,number(c,"quantity")),Long::sum);
            boolean done=!needed.isEmpty()&&!needed.containsKey(0L)&&needed.entrySet().stream().allMatch(e->left.getOrDefault(e.getKey(),0L)>=e.getValue());unit.put("shipped_complete",done);
            if(done){count++;for(Map.Entry<Long,Long> e:needed.entrySet())left.compute(e.getKey(),(k,v)->v-e.getValue());}
        }return count;
    }
    static void decorateSources(Map<String,Object> parent,Map<Long,Long> shipped){Map<String,Object> snapshot=OrderBundles.object(parent.get("snapshot"));parent.put("shipped_sets",completeSources(snapshot,shipped));parent.put("units",snapshot.get("units"));parent.put("sales_unit","袋");parent.put("business_type","LUCKY_BAG");}
    static void decorate(Map<String,Object> parent,List<Map<String,Object>> children){Map<String,Object> snapshot=OrderBundles.object(parent.get("snapshot"));Map<String,Long> shipped=new HashMap<>();for(Map<String,Object> c:children)shipped.merge(text(c,"sku_sn"),number(c,"shipped_quantity"),Long::sum);parent.put("shipped_sets",complete(snapshot,shipped));parent.put("units",snapshot.get("units"));parent.put("sales_unit","袋");parent.put("business_type","LUCKY_BAG");}
}
