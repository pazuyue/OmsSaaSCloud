package com.oms.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Pure evaluation against purchased sales lines. Gifts never become condition inputs. */
public final class GiftRules {
    private GiftRules() {}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) {
        if(value instanceof Map)return new LinkedHashMap<>((Map<String,Object>)value);
        try{return JSON.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new IllegalArgumentException("赠品配置或快照无法解析");}
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object value) {
        require(value instanceof List,"赠品明细格式无效");List<Map<String,Object>> out=new ArrayList<>();
        for(Object item:(List<?>)value){require(item instanceof Map,"赠品明细格式无效");out.add(new LinkedHashMap<>((Map<String,Object>)item));}return out;
    }
    static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),pairs[i+1]);return m;}
    static BigDecimal decimal(Object v){try{BigDecimal d=new BigDecimal(String.valueOf(v));require(d.scale()<=2&&d.signum()>=0&&d.compareTo(new BigDecimal("1000000000"))<=0,"金额应为不超过十亿的非负数，最多两位小数");return d;}catch(NumberFormatException e){throw new IllegalArgumentException("赠品条件金额无效");}}
    static String required(Map<String,Object> m,String key,int max){String v=text(m,key).trim();require(!v.isEmpty()&&v.length()<=max,"请填写有效的"+key);return v;}
    static Map<String,Object> normalize(Map<String,Object> input){
        List<Map<String,Object>> rules=rows(input.get("rules"));require(!rules.isEmpty()&&rules.size()<=30,"一个策略组须配置1至30条策略");
        Object shopValue=input.get("channels");require(shopValue instanceof List&&!((List<?>)shopValue).isEmpty()&&((List<?>)shopValue).size()<=100,"请选择1至100个适用店铺");
        Set<Long> shops=new LinkedHashSet<>();for(Object value:(List<?>)shopValue){long id=Long.parseLong(String.valueOf(value));require(id>0&&shops.add(id),"适用店铺无效或重复");}
        String start=required(input,"start_time",19),end=required(input,"end_time",19);require(time(end).isAfter(time(start)),"活动结束时间须晚于开始时间");
        Set<String> ids=new HashSet<>();List<Map<String,Object>> clean=new ArrayList<>();
        for(Map<String,Object> r:rules){String key=required(r,"key",40),type=text(r,"type");require(key.matches("[A-Za-z0-9_-]{1,40}")&&ids.add(key),"策略编号无效或重复");require(Arrays.asList("AMOUNT","PRODUCT","MULTIPLE","TIER").contains(type),"赠品策略类型无效");
            List<String> skus=new ArrayList<>();require(r.get("skus") instanceof List,"购买SKU范围无效");for(Object value:(List<?>)r.get("skus")){String sku=String.valueOf(value).trim();require(!sku.isEmpty()&&sku.length()<=128&&!skus.contains(sku),"购买SKU无效或重复");skus.add(sku);}require(skus.size()<=100,"购买SKU最多100项");require("AMOUNT".equals(type)||!skus.isEmpty(),"商品、倍赠及阶梯策略须指定购买SKU");
            long minimum=number(r,"minimum"),cap=number(r,"cap");require(minimum>=1&&minimum<=100000000&&cap>=1&&cap<=100000,"门槛数量及赠送倍数上限无效");
            List<Map<String,Object>> gifts=normalizeGifts(r.get("gifts"));List<Map<String,Object>> tiers=new ArrayList<>();
            if("TIER".equals(type)){long previous=0;for(Map<String,Object> tier:rows(r.get("tiers"))){long at=number(tier,"minimum");require(at>previous&&at<=100000000,"阶梯数量门槛须从小到大且不重复");tiers.add(map("minimum",at,"gifts",normalizeGifts(tier.get("gifts"))));previous=at;}require(!tiers.isEmpty()&&tiers.size()<=20,"请配置1至20个阶梯");}
            clean.add(map("key",key,"name",required(r,"name",100),"type",type,"amount",decimal(r.get("amount")),"minimum",minimum,"cap",cap,"skus",skus,"gifts",gifts,"tiers",tiers,"stop",Boolean.TRUE.equals(r.get("stop"))));
        }
        String recovery=text(input,"shipped_policy");require(Arrays.asList("RETURN","KEEP").contains(recovery),"请选择已发赠品的售后处理方式");
        return map("channels",new ArrayList<>(shops),"start_time",start,"end_time",end,"cross_group_stack",Boolean.TRUE.equals(input.get("cross_group_stack")),"rules",clean,"shipped_policy",recovery,"allow_waive",Boolean.TRUE.equals(input.get("allow_waive")),"difference_affects_amount",Boolean.TRUE.equals(input.get("difference_affects_amount")),"pause_pending",true,"shortage_policy","WAIT");
    }
    private static List<Map<String,Object>> normalizeGifts(Object value){List<Map<String,Object>> out=new ArrayList<>();Set<String> seen=new HashSet<>();for(Map<String,Object> gift:rows(value)){String sku=required(gift,"sku_sn",128);long q=number(gift,"quantity");require(seen.add(sku)&&q>0&&q<=100000,"赠品SKU重复或数量无效");out.add(map("sku_sn",sku,"quantity",q,"goods_name",text(gift,"goods_name")));}require(!out.isEmpty()&&out.size()<=20,"每条策略须配置1至20种赠品");return out;}
    @SuppressWarnings("unchecked") static Map<String,Object> rule(Map<String,Object> r,List<Map<String,Object>> purchased){
        long qty=0;BigDecimal amount=BigDecimal.ZERO;List<?> skus=(List<?>)r.get("skus");
        for(Map<String,Object> l:purchased){require(!"GIFT".equals(text(l,"line_kind")),"赠品不能参与赠送门槛计算");if(skus.isEmpty()||skus.contains(text(l,"sku_sn"))){qty=Math.addExact(qty,number(l,"quantity"));amount=amount.add(new BigDecimal(String.valueOf(l.get("payment"))));}}
        String type=text(r,"type");long multiplier=1;List<Map<String,Object>> gifts=rows(r.get("gifts"));boolean hit;
        if("AMOUNT".equals(type))hit=qty>0&&amount.compareTo(decimal(r.get("amount")))>=0;
        else if("TIER".equals(type)){hit=false;for(Map<String,Object> tier:rows(r.get("tiers")))if(qty>=number(tier,"minimum")){hit=true;gifts=rows(tier.get("gifts"));}}
        else{hit=qty>=number(r,"minimum");if("MULTIPLE".equals(type))multiplier=Math.min(number(r,"cap"),qty/number(r,"minimum"));}
        List<Map<String,Object>> out=new ArrayList<>();if(hit)for(Map<String,Object> g:gifts)out.add(map("sku_sn",g.get("sku_sn"),"goods_name",g.get("goods_name"),"quantity",Math.multiplyExact(number(g,"quantity"),multiplier)));
        return map("key",r.get("key"),"name",r.get("name"),"matched",hit,"reason",hit?"满足赠送条件":"未达到购买数量或商品金额门槛","basis_quantity",qty,"basis_amount",amount,"gifts",out);
    }
    static Map<String,Object> evaluate(List<Map<String,Object>> groups,long channel,String paid,List<Map<String,Object>> purchased){
        List<Map<String,Object>> selected=new ArrayList<>(),trace=new ArrayList<>(),entitlements=new ArrayList<>();
        for(Map<String,Object> g:groups){Map<String,Object> p=object(g.get("policy"));boolean applies=((List<?>)p.get("channels")).stream().anyMatch(v->String.valueOf(v).equals(String.valueOf(channel)))&&time(paid).compareTo(time(text(p,"start_time")))>=0&&time(paid).compareTo(time(text(p,"end_time")))<=0;
            Map<String,Object> t=map("group_id",g.get("id"),"name",g.get("name"),"priority",g.get("priority"),"matched",false,"rules",new ArrayList<>());trace.add(t);if(!applies){t.put("reason","店铺或付款时间不在活动范围");continue;}
            List<Map<String,Object>> executions=new ArrayList<>();boolean matched=false;for(Map<String,Object> r:rows(p.get("rules"))){Map<String,Object> e=rule(r,purchased);executions.add(e);if(Boolean.TRUE.equals(e.get("matched"))){matched=true;for(Map<String,Object> gift:rows(e.get("gifts"))){gift.put("group_id",g.get("id"));gift.put("group_name",g.get("name"));gift.put("rule_key",r.get("key"));gift.put("rule_name",r.get("name"));gift.put("policy",p);entitlements.add(gift);require(entitlements.size()<=1000,"本订单命中赠品权益超过1000条，请收敛叠加策略范围");}if(Boolean.TRUE.equals(r.get("stop")))break;}}
            t.put("rules",executions);t.put("matched",matched);t.put("reason",matched?"命中策略组":"组内策略均未命中，继续下一组");if(matched){selected.add(g);if(!Boolean.TRUE.equals(p.get("cross_group_stack")))break;}
        }
        return map("groups",selected,"entitlements",entitlements,"trace",trace,"gifts",aggregate(entitlements));
    }
    static List<Map<String,Object>> aggregate(List<Map<String,Object>> items){Map<String,Map<String,Object>> bySku=new LinkedHashMap<>();for(Map<String,Object> g:items){String sku=text(g,"sku_sn");Map<String,Object> total=bySku.computeIfAbsent(sku,k->map("sku_sn",sku,"goods_name",g.get("goods_name"),"quantity",0L,"payment",BigDecimal.ZERO,"platform_oid","","line_kind","GIFT"));total.put("quantity",Math.addExact(number(total,"quantity"),number(g,"quantity")));require(number(total,"quantity")<=100000000,"单SKU赠品数量超过上限");}return new ArrayList<>(bySku.values());}
    /** Only originally granted rule/SKU entitlements may survive a recheck; no new promotion is granted. */
    static List<Map<String,Object>> retained(Map<String,Object> snapshot,List<Map<String,Object>> purchased,boolean shipped){
        List<Map<String,Object>> out=new ArrayList<>();Map<String,Map<String,Object>> groups=new HashMap<>();for(Map<String,Object> g:rows(snapshot.get("groups")))groups.put(text(g,"id"),g);
        for(Map<String,Object> original:rows(snapshot.get("entitlements"))){Map<String,Object> p=object(groups.get(text(original,"group_id")).get("policy"));long qty=0;
            if(shipped&&"KEEP".equals(text(p,"shipped_policy")))qty=number(original,"quantity");
            else{for(Map<String,Object> r:rows(p.get("rules")))if(text(original,"rule_key").equals(text(r,"key")))for(Map<String,Object> now:rows(rule(r,purchased).get("gifts")))if(text(now,"sku_sn").equals(text(original,"sku_sn")))qty=Math.min(number(original,"quantity"),number(now,"quantity"));}
            if(qty>0){Map<String,Object> gift=new LinkedHashMap<>(original);gift.put("quantity",qty);out.add(gift);}
        }return aggregate(out);
    }
    static BigDecimal proportional(Object payment,long remaining,long initial){return initial==0?BigDecimal.ZERO:new BigDecimal(String.valueOf(payment)).multiply(BigDecimal.valueOf(remaining)).divide(BigDecimal.valueOf(initial),2,RoundingMode.HALF_UP);}
}
