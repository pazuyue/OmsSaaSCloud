package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

public final class OrderAllocationPolicy {
    private OrderAllocationPolicy(){}
    public static Map<String,Object> defaults(Map<String,Object> input){Map<String,Object> result=new LinkedHashMap<>(input);result.putIfAbsent("strategy","FEWEST_SHIPMENTS");result.putIfAbsent("execution_mode","MANUAL");result.putIfAbsent("auto_replenish",0);for(String key:Arrays.asList("allow_bundle_split","allow_bundle_partial_components","max_virtual_warehouses","max_shipments","min_first_allocation_percent"))result.putIfAbsent(key,0);return result;}
    public static String province(String value){return value.trim().replaceAll("(壮族|回族|维吾尔)?自治区$|特别行政区$|省$|市$","");}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> validate(Map<String,Object> input,List<Map<String,Object>> catalog){
        input=defaults(input);Map<String,Object> result=new LinkedHashMap<>();String strategy=text(input,"strategy");require(Arrays.asList("FEWEST_SHIPMENTS","REGION_FIRST").contains(strategy),"分仓策略无效");result.put("strategy",strategy);String mode=text(input,"execution_mode");require(Arrays.asList("MANUAL","AUTO").contains(mode),"分仓执行方式无效");result.put("execution_mode",mode);require(number(input,"auto_replenish")==0||number(input,"auto_replenish")==1,"自动补充分仓开关无效");result.put("auto_replenish",number(input,"auto_replenish"));for(String key:Arrays.asList("max_virtual_warehouses","max_shipments","min_first_allocation_percent")){long value=number(input,key);require(value>=0&&value<=100,"分仓上限和比例须为0至100的整数");result.put(key,value);}
        for(String flag:Arrays.asList("enabled","allow_multi","allow_partial","allow_split_sku","allow_bundle_split","allow_bundle_partial_components")){
            require(number(input,flag)==0||number(input,flag)==1,"分仓开关值无效");result.put(flag,number(input,flag));}
        require(number(input,"allow_split_sku")==0||number(input,"allow_multi")==1,"同SKU跨仓需先允许多仓发货");
        require(number(input,"allow_bundle_split")==0||number(input,"allow_multi")==1,"套内组件跨仓需先允许多仓发货");require(number(input,"allow_bundle_partial_components")==0||number(input,"allow_partial")==1,"套内组件部分发货需先允许部分发货");
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
