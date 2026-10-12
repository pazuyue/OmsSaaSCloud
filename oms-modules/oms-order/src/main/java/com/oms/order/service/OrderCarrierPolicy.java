package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Deterministic province/physical warehouse routing, shared by auto and manual selection. */
public final class OrderCarrierPolicy {
    private OrderCarrierPolicy(){}
    public static final List<String> PROVINCES=Collections.unmodifiableList(Arrays.asList("北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆","香港","澳门","台湾"));
    public static String province(String value){
        String p=value==null?"":value.trim();
        for(String suffix:Arrays.asList("维吾尔自治区","壮族自治区","回族自治区","特别行政区","自治区","省","市"))if(p.endsWith(suffix)){p=p.substring(0,p.length()-suffix.length());break;}
        return PROVINCES.contains(p)?p:"";
    }
    public static Map<String,Object> defaults(){Map<String,Object> p=new LinkedHashMap<>();p.put("enabled",0);p.put("carrier_order",new ArrayList<>());p.put("routes",new ArrayList<>());return p;}
    public static List<String> codes(Object value,boolean empty){
        require(value instanceof List&&((List<?>)value).size()<=50,"快递列表无效或超过50项");List<String> result=new ArrayList<>();
        for(Object item:(List<?>)value){require(item instanceof String&&((String)item).matches("[A-Za-z0-9_-]{1,64}")&&!result.contains(item),"快递编码无效或重复");result.add((String)item);}
        require(empty||!result.isEmpty(),"请选择允许快递及优先顺序");return result;
    }
    public static List<String> provinces(Object value){
        require(value instanceof List&&((List<?>)value).size()<=34,"省份列表无效");List<String> result=new ArrayList<>();
        for(Object item:(List<?>)value){require(item instanceof String,"省份无效");String p=province((String)item);require(!p.isEmpty()&&!result.contains(p),"省份无效或重复");result.add(p);}return result;
    }
    public static String warehouseKey(Map<String,Object> row){return encode(Arrays.asList(text(row,"owner_code"),text(row,"real_store_code")));}
    @SuppressWarnings("unchecked") public static Map<String,Object> validate(Map<String,Object> input,Set<String> carriers,Set<String> warehouses){
        require(input.containsKey("enabled")&&Arrays.asList(0L,1L).contains(number(input,"enabled")),"自动匹配开关无效");List<String> allowed=codes(input.get("carrier_order"),number(input,"enabled")==0);require(carriers.containsAll(allowed),"允许快递包含不存在或已停用的快递");
        Object raw=input.get("routes");require(raw instanceof List&&((List<?>)raw).size()<=100,"优先规则无效或超过100条");List<Map<String,Object>> routes=new ArrayList<>();
        for(Object item:(List<?>)raw){require(item instanceof Map,"优先规则无效");Map<String,Object> r=(Map<String,Object>)item;String owner=text(r,"owner_code"),store=text(r,"real_store_code");require(owner.isEmpty()==store.isEmpty(),"请完整选择货主和实体仓");require(store.isEmpty()||warehouses.contains(warehouseKey(r)),"优先规则仓库已停用或不属于公司");List<String> ps=provinces(r.get("provinces")),order=codes(r.get("carrier_order"),false);require(!store.isEmpty()||!ps.isEmpty(),"全仓全省份请使用店铺默认顺序");require(allowed.containsAll(order),"优先快递必须属于店铺允许快递");
            for(Map<String,Object> prior:routes)if(owner.equals(text(prior,"owner_code"))&&store.equals(text(prior,"real_store_code"))){List<String> previous=(List<String>)prior.get("provinces");require(!(ps.isEmpty()&&previous.isEmpty())&&(ps.isEmpty()||previous.isEmpty()||Collections.disjoint(ps,previous)),"相同仓库的省份优先规则重叠");}
            Map<String,Object> normalized=new LinkedHashMap<>();normalized.put("owner_code",owner);normalized.put("real_store_code",store);normalized.put("provinces",ps);normalized.put("carrier_order",order);routes.add(normalized);
        }
        Map<String,Object> result=defaults();result.put("enabled",number(input,"enabled"));result.put("carrier_order",allowed);result.put("routes",routes);return result;
    }
    /** Specific warehouse+province > warehouse default > province default > shop default. */
    @SuppressWarnings("unchecked") public static List<String> ordered(Map<String,Object> policy,Map<String,Object> shipment){
        List<String> result=(List<String>)policy.get("carrier_order");int best=0;
        for(Map<String,Object> route:(List<Map<String,Object>>)policy.get("routes")){
            boolean warehouse=!text(route,"real_store_code").isEmpty();List<String> ps=(List<String>)route.get("provinces");
            if(warehouse&&!warehouseKey(route).equals(warehouseKey(shipment)))continue;
            if(!ps.isEmpty()&&!ps.contains(text(shipment,"receiver_province")))continue;
            int score=(warehouse?2:0)+(!ps.isEmpty()?1:0);if(score>best){best=score;result=(List<String>)route.get("carrier_order");}
        }
        // A route is a priority, never a bypass of the shop allow-list.
        LinkedHashSet<String> candidates=new LinkedHashSet<>(result);candidates.addAll((List<String>)policy.get("carrier_order"));return new ArrayList<>(candidates);
    }
    @SuppressWarnings("unchecked") public static String unavailable(Map<String,Object> carrier,Map<String,Object> shipment){
        if(number(carrier,"enabled")!=1)return "快递已停用";
        String province=text(shipment,"receiver_province");if(province.isEmpty())return "收货省份缺失或无效，请核对来源订单地址";
        for(Map<String,Object> service:(List<Map<String,Object>>)carrier.get("services"))if(warehouseKey(service).equals(warehouseKey(shipment))){
            List<String> allowed=(List<String>)service.get("provinces"),denied=(List<String>)service.get("excluded_provinces");
            if(denied.contains(province)||(!allowed.isEmpty()&&!allowed.contains(province)))return "该仓快递不配送收货省份";
            return "";
        }
        return "未配置该货主实体仓的快递服务";
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> service(Map<String,Object> carrier,Map<String,Object> shipment){return ((List<Map<String,Object>>)carrier.get("services")).stream().filter(s->warehouseKey(s).equals(warehouseKey(shipment))).findFirst().orElseThrow(()->new IllegalArgumentException("仓库快递映射缺失"));}
}
