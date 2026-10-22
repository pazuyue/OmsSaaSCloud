package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Gift entitlement uses parent units; stock and recovery use immutable physical contents. */
final class GiftProducts {
    private GiftProducts(){}
    static final class StockShortage extends IllegalArgumentException { StockShortage(String message){super(message);} }
    static Map<String,Object> definition(String sku,Map<String,Object> goods){
        require(number(goods,"is_gift")==1,"商品未开启允许作为赠品："+sku);
        require(!(number(goods,"is_fd")==1&&number(goods,"is_package")==1),"商品形态冲突："+sku);
        if(number(goods,"is_fd")==1){Map<String,Object> snapshot=OrderLuckyBags.definition(sku,text(goods,"goods_name"),goods);require(number(snapshot,"enabled")==1,"赠品福袋规则已停用："+sku);return snapshot;}
        if(number(goods,"is_package")==1)return OrderBundles.snapshot(sku,text(goods,"goods_name"),goods);
        return Collections.emptyMap();
    }
    static List<Map<String,Object>> freeze(List<Map<String,Object>> gifts,Map<String,Map<String,Object>> goods,long plan){
        List<Map<String,Object>> result=new ArrayList<>();int index=0;
        for(Map<String,Object> source:gifts){String sku=text(source,"sku_sn");Map<String,Object> row=new LinkedHashMap<>(source),product=goods.get(sku);require(product!=null,"赠品资料缺失："+sku);
            Map<String,Object> snapshot=definition(sku,product);if(!snapshot.isEmpty())row.put("bundle_snapshot",snapshot);
            if(OrderLuckyBags.lucky(snapshot))require(number(row,"quantity")<=50,"单种福袋赠品每单最多50袋："+sku);
            row.put("goods_name",text(product,"goods_name").isEmpty()?source.get("goods_name"):product.get("goods_name"));row.put("sales_unit",unit(row));row.put("stock_reference","GIFT:"+plan+":"+(++index));result.add(row);
        }return result;
    }
    static String key(Map<String,Object> line){return text(line,"stock_reference")+"/"+text(line,"sku_sn");}
    static String unit(Map<String,Object> parent){Map<String,Object> snapshot=OrderBundles.object(parent.get("bundle_snapshot"));return OrderLuckyBags.lucky(snapshot)?"袋":snapshot.isEmpty()?"件":"套";}
    static List<Map<String,Object>> expand(List<Map<String,Object>> parents){
        List<Map<String,Object>> result=OrderBundles.expand(parents);
        for(Map<String,Object> line:result){Map<String,Object> parent=parents.stream().filter(p->text(p,"stock_reference").equals(text(line,"stock_reference"))).findFirst().get();line.put("gift_parent_sku",parent.get("sku_sn"));line.put("gift_parent_name",parent.get("goods_name"));line.put("gift_parent_quantity",parent.get("quantity"));line.put("gift_parent_unit",unit(parent));}
        return result;
    }
    static Map<String,Long> physicalEntitlement(List<Map<String,Object>> parents,List<Map<String,Object>> retained){
        Map<String,Long> quantities=new HashMap<>(),result=new HashMap<>();for(Map<String,Object> row:retained)quantities.put(text(row,"sku_sn"),number(row,"quantity"));
        for(Map<String,Object> parent:parents){long count=Math.min(number(parent,"quantity"),quantities.getOrDefault(text(parent,"sku_sn"),0L));Map<String,Object> snapshot=OrderBundles.object(parent.get("bundle_snapshot"));
            if(OrderLuckyBags.lucky(snapshot)){List<Map<String,Object>> units=com.oms.common.lucky.LuckyBagRules.rows(snapshot.get("units"));require(units.size()==number(parent,"quantity"),"赠品福袋原组成快照不完整");for(int i=0;i<count;i++)for(Map<String,Object> c:com.oms.common.lucky.LuckyBagRules.rows(units.get(i).get("components")))result.merge(text(parent,"stock_reference")+"/"+text(c,"sku_sn"),Math.max(1,number(c,"quantity")),Long::sum);}
            else if(!snapshot.isEmpty()&&!"STOCKED".equals(text(snapshot,"inventory_mode")))for(Map<String,Object> c:com.oms.common.lucky.LuckyBagRules.rows(snapshot.get("components")))result.put(text(parent,"stock_reference")+"/"+text(c,"sku_sn"),Math.multiplyExact(count,number(c,"quantity")));
            else result.put(key(parent),count);
        }return result;
    }
}
