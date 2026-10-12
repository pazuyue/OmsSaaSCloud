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
    public List<Map<String,Object>> warehouses(String company){return data(warehouses.stores(company,SecurityConstants.INNER));}
    public List<Map<String,Object>> channels(String company){return data(channels.channels(company,SecurityConstants.INNER));}
    public Map<String,List<Map<String,Object>>> goods(String company,List<String> skus){Map<String,List<Map<String,Object>>> result=new HashMap<>();for(Map<String,Object> row:data(goods.goods(company,skus,SecurityConstants.INNER)))result.computeIfAbsent(text(row,"sku_sn"),k->new ArrayList<>()).add(row);return result;}
    public Map<String,Object> validateShop(String company,long channel){
        Map<String,Object> selected=channels(company).stream().filter(c->number(c,"channel_id")==channel).findFirst().orElseThrow(()->new IllegalArgumentException("店铺不存在或不属于当前公司"));
        require(number(selected,"enabled")==1,"店铺已停用");require("TM".equals(text(selected,"channel_type")),"一期仅支持天猫店铺");return selected;
    }
}
