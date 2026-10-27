package com.oms.order.service;
import com.oms.common.api.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

@Service
public class OrderCatalog {
    @Resource private OrderChannelClient channels;
    @Resource private OrderGoodsClient goods;
    @Resource private com.oms.common.api.OrderWarehouseClient warehouses;
    public Map<String,Object> searchGoods(String company,Map<String,Object> query){return data(goods.search(company,query,SecurityConstants.INNER));}
    public void protectGift(String company,long group,String name,String token,Collection<String> skus){Map<String,Object> input=new HashMap<>();input.put("group_id",group);input.put("group_name",name);input.put("request_token",token);input.put("skus",new ArrayList<>(skus));require(Boolean.TRUE.equals(data(goods.protectGift(company,input,SecurityConstants.INNER))),"赠品商品引用保护未确认");}
    public void refreshGift(String company,long group){Map<String,Object> input=new HashMap<>();input.put("group_id",group);require(Boolean.TRUE.equals(data(goods.refreshGift(company,input,SecurityConstants.INNER))),"赠品商品引用刷新未确认");}
    public List<Map<String,Object>> warehouses(String company){return data(warehouses.stores(company,SecurityConstants.INNER));}
    public Map<String,Object> carrierContext(String company){return data(warehouses.carrierContext(company,SecurityConstants.INNER));}
    public List<Map<String,Object>> channels(String company){return data(channels.channels(company,SecurityConstants.INNER));}
    public Map<String,List<Map<String,Object>>> goods(String company,List<String> skus){Map<String,List<Map<String,Object>>> result=new HashMap<>();for(Map<String,Object> row:data(goods.goods(company,skus,SecurityConstants.INNER)))result.computeIfAbsent(text(row,"sku_sn"),k->new ArrayList<>()).add(row);return result;}
    static String bindingKey(TmallOrderParser.Line l){return l.itemId+"/"+(l.skuId.isEmpty()?"0":l.skuId);}
    public Map<String,Map<String,Object>> productBindings(String company,long channel,List<TmallOrderParser.Line> lines){
        List<Map<String,Object>> identities=new ArrayList<>();for(TmallOrderParser.Line l:lines)if(l.itemId.matches("[1-9][0-9]{0,18}")&&(l.skuId.isEmpty()||l.skuId.matches("0|[1-9][0-9]{0,18}"))){Map<String,Object> i=new LinkedHashMap<>();i.put("item_id",l.itemId);i.put("sku_id",l.skuId.isEmpty()?"0":l.skuId);identities.add(i);}
        Map<String,Map<String,Object>> result=new HashMap<>();if(identities.isEmpty())return result;
        for(Map<String,Object> row:data(channels.productBindings(company,channel,identities,SecurityConstants.INNER)))result.put(text(row,"item_id")+"/"+text(row,"sku_id"),row);return result;
    }
    public Map<String,Object> validateShop(String company,long channel){
        Map<String,Object> selected=channels(company).stream().filter(c->number(c,"channel_id")==channel).findFirst().orElseThrow(()->new IllegalArgumentException("店铺不存在或不属于当前公司"));
        require(number(selected,"enabled")==1,"店铺已停用");require("TM".equals(text(selected,"channel_type")),"一期仅支持天猫店铺");return selected;
    }
}
