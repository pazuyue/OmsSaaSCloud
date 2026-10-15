package com.ruoyi.system.company;
import java.util.*;
public final class CompanyServices {
    private CompanyServices(){}
    public static final Map<String,String> APPS;
    public static final Map<String,String> NAMES;
    static {Map<String,String> a=new LinkedHashMap<>(),n=new LinkedHashMap<>();a.put("order","oms-order");a.put("inventory","oms-inventory");a.put("supplychain","oms-supplychain");a.put("goods","oms-modules-goods");a.put("channel","oms-channel");n.put("order","订单");n.put("inventory","库存");n.put("supplychain","供应链");n.put("goods","商品");n.put("channel","渠道");APPS=Collections.unmodifiableMap(a);NAMES=Collections.unmodifiableMap(n);}
    public static String company(String value){require(value!=null&&value.trim().matches("[A-Za-z0-9_-]{1,64}"),"公司编码须为1至64位字母、数字、下划线或短横线");return value.trim().toUpperCase(Locale.ROOT);}
    public static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    public static long number(Map<String,?> row,String key){Object value=row.get(key);return value==null?0:Long.parseLong(value.toString());}
    public static String text(Map<String,?> row,String key){Object value=row.get(key);return value==null?"":value.toString();}
    public static Map<String,Object> map(Object... pairs){Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)value.put(String.valueOf(pairs[i]),pairs[i+1]);return value;}
}
