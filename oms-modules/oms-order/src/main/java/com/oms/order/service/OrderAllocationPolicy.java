package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

public final class OrderAllocationPolicy {
    private OrderAllocationPolicy(){}
    public static String province(String value){return value.trim().replaceAll("(壮族|回族|维吾尔)?自治区$|特别行政区$|省$|市$","");}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> validate(Map<String,Object> input,List<Map<String,Object>> catalog){
        Map<String,Object> result=new LinkedHashMap<>();for(String flag:Arrays.asList("enabled","allow_multi","allow_partial","allow_split_sku")){
            require(number(input,flag)==0||number(input,flag)==1,"分仓开关值无效");result.put(flag,number(input,flag));}
        require(number(input,"allow_split_sku")==0||number(input,"allow_multi")==1,"同SKU跨仓需先允许多仓发货");
        Object order=input.get("warehouse_order");require(order instanceof List&&((List<?>)order).size()<=100,"请选择允许仓并设置默认顺序");
        List<String> allowed=new ArrayList<>();Map<String,Map<String,Object>> stores=new HashMap<>();for(Map<String,Object> store:catalog)stores.put(text(store,"wms_simulation_code"),store);
        String owner=text(input,"owner_code");for(Object code:(List<?>)order){require(code instanceof String&&!allowed.contains(code),"允许仓重复或无效");
            Map<String,Object> store=stores.get(code);require(store!=null&&owner.equals(text(store,"owner_code")),"允许仓已停用、映射异常或不属于所选货主");allowed.add((String)code);}
        require(number(input,"enabled")==0||!allowed.isEmpty(),"启用分仓前请配置货主和允许仓");
        result.put("owner_code",owner);result.put("warehouse_order",allowed);
        Object priorities=input.get("province_priorities");require(priorities instanceof Map&&((Map<?,?>)priorities).size()<=40,"省份优先仓配置无效");
        Map<String,List<String>> normalized=new LinkedHashMap<>();for(Map.Entry<?,?> entry:((Map<?,?>)priorities).entrySet()){
            String key=province(String.valueOf(entry.getKey()));require(!key.isEmpty()&&key.length()<=20&&!normalized.containsKey(key),"省份为空或重复");
            require(entry.getValue() instanceof List&&!((List<?>)entry.getValue()).isEmpty(),"省份优先仓不能为空");List<String> codes=new ArrayList<>();
            for(Object code:(List<?>)entry.getValue()){require(code instanceof String&&allowed.contains(code)&&!codes.contains(code),"省份优先仓必须来自店铺允许仓，且不能重复");codes.add((String)code);}normalized.put(key,codes);
        }
        result.put("province_priorities",normalized);return result;
    }
    @SuppressWarnings("unchecked")
    public static List<String> ordered(Map<String,Object> policy,String province){
        List<String> defaults=(List<String>)policy.get("warehouse_order");Map<String,List<String>> priorities=(Map<String,List<String>>)policy.get("province_priorities");
        LinkedHashSet<String> result=new LinkedHashSet<>(priorities.getOrDefault(province(province),Collections.emptyList()));result.addAll(defaults);return new ArrayList<>(result);
    }
}
