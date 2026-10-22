package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.math.BigDecimal;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.GiftRules.*;

/** Draft tools only read the company goods catalog and run the production evaluator. */
@Service public class GiftStrategyDesigner {
    @Resource OrderCatalog catalog;
    @Resource GiftService gifts;

    public List<Map<String,Object>> resolve(String company,Map<String,Object> input){
        require(input.get("skus") instanceof List,"请提供SKU列表");List<?> values=(List<?>)input.get("skus");
        require(!values.isEmpty()&&values.size()<=200,"单次请选择1至200个SKU");
        Set<String> skus=new LinkedHashSet<>();for(Object value:values){require(value instanceof String,"SKU格式无效");String sku=((String)value).trim();require(!sku.isEmpty()&&sku.length()<=128,"SKU格式无效");skus.add(sku);}
        String use=text(input,"use");require(Arrays.asList("PURCHASE","GIFT").contains(use),"请选择商品用途");
        Map<String,List<Map<String,Object>>> found=catalog.goods(company,new ArrayList<>(skus));List<Map<String,Object>> result=new ArrayList<>();
        for(String sku:skus){List<Map<String,Object>> matches=found.getOrDefault(sku,Collections.emptyList());Map<String,Object> row=matches.size()==1?matches.get(0):Collections.emptyMap();
            String error=matches.size()!=1?"商品不存在或SKU不唯一":"";Map<String,Object> definition=Collections.emptyMap();if(error.isEmpty()&&"GIFT".equals(use))try{definition=GiftProducts.definition(sku,row);}catch(IllegalArgumentException invalid){error=invalid.getMessage();}
            String type=number(row,"is_fd")==1?"LUCKY_BAG":number(row,"is_package")==1?"BUNDLE":"NORMAL";
            result.add(map("skuSn",sku,"goodsName",text(row,"goods_name"),"isGift",number(row,"is_gift"),"productType",type,"salesUnit","LUCKY_BAG".equals(type)?"袋":"BUNDLE".equals(type)?"套":"件","inventoryMode",definition.get("inventory_mode"),"specs",(text(row,"color_name")+" "+text(row,"size_name")).trim(),"valid",error.isEmpty(),"error",error));
        }return result;
    }

    public Map<String,Object> simulate(String company,Map<String,Object> input){
        Map<String,Object> policy=gifts.checkedPolicy(company,object(input.get("policy")));
        String paid=required(input,"paid_time",19);time(paid);
        List<Map<String,Object>> lines=rows(input.get("lines"));require(!lines.isEmpty()&&lines.size()<=100,"模拟购物清单须包含1至100种商品");
        Set<String> seen=new LinkedHashSet<>();List<Map<String,Object>> basis=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> line:lines){String sku=required(line,"sku_sn",128);require(seen.add(sku),"模拟清单的SKU不能重复");require(!"GIFT".equals(text(line,"line_kind")),"赠品不能参与赠送门槛计算");long quantity=number(line,"quantity");require(quantity>0&&quantity<=100000000,"模拟购买数量无效");BigDecimal payment=decimal(line.get("payment"));total=total.add(payment);require(total.compareTo(new BigDecimal("1000000000"))<=0,"模拟商品实付合计不能超过十亿元");basis.add(map("sku_sn",sku,"quantity",quantity,"payment",payment,"line_kind","SALE"));}
        Map<String,List<Map<String,Object>>> products=catalog.goods(company,new ArrayList<>(seen));for(String sku:seen)require(products.getOrDefault(sku,Collections.emptyList()).size()==1,"模拟购买SKU不存在或不唯一："+sku);
        policy.put("channels",Collections.singletonList(1));policy.put("cross_group_stack",false);
        long id=number(input,"group_id");Map<Long,Map<String,Object>> usage=Collections.emptyMap();
        if(id>0){require(gifts.repository.db().queryForObject("SELECT COUNT(*) FROM oms_gift_group WHERE company_code=? AND id=?",Long.class,company,id)==1,"策略组不存在或无权访问");usage=gifts.quota().usages(company,Collections.singletonList(id));}
        Map<String,Object> group=map("id",id,"name",text(input,"name").isEmpty()?"当前策略组":text(input,"name"),"priority",1,"revision",0,"policy",policy);
        List<Map<String,Object>> groups=Collections.singletonList(group);
        Map<String,Object> result=evaluate(groups,1,paid,calculate(groups,1,paid,basis),GiftQuota.preview(usage));result.put("simulated",true);result.put("paid_time",paid);result.put("payment",total);result.put("quota_note","仅参考当前剩余额度，不占用次数；实际转单以并发申请结果为准");return result;
    }
}
