package com.oms.order.service;

import java.math.BigDecimal;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Pure local rules. Any manual condition wins over a stock wait; fulfillment blocks always win. */
public final class OrderAutoReviewPolicy {
    private OrderAutoReviewPolicy(){}
    public static Map<String,Object> defaults(){Map<String,Object> p=new LinkedHashMap<>();p.put("mode","MANUAL");p.put("stock_condition","ANY");p.put("manual_match_required",1);p.put("amount_enabled",0);p.put("amount_threshold","0.00");p.put("quantity_enabled",0);p.put("quantity_threshold",0);p.put("review_skus",Collections.emptyList());return p;}
    public static Map<String,Object> validate(Map<String,Object> input){
        Map<String,Object> p=defaults();p.putAll(input);require(Arrays.asList("AUTO","MANUAL").contains(text(p,"mode")),"请选择审单方式");require(Arrays.asList("ANY","FULL_RESERVED").contains(text(p,"stock_condition")),"请选择库存审核条件");
        Map<String,Object> result=new LinkedHashMap<>();result.put("mode",text(p,"mode"));result.put("stock_condition",text(p,"stock_condition"));
        for(String flag:Arrays.asList("manual_match_required","amount_enabled","quantity_enabled")){long value=number(p,flag);require(value==0||value==1,"审单规则开关无效");result.put(flag,value);}
        BigDecimal amount;try{amount=new BigDecimal(text(p,"amount_threshold")).setScale(2,java.math.RoundingMode.UNNECESSARY);}catch(Exception e){throw new IllegalArgumentException("金额阈值须为最多两位小数");}
        require(amount.signum()>=0&&amount.compareTo(new BigDecimal("999999999999.99"))<=0&&(number(p,"amount_enabled")==0||amount.signum()>0),"启用金额规则后，请填写大于0的金额阈值");result.put("amount_threshold",amount.toPlainString());
        long quantity=number(p,"quantity_threshold");require(quantity>=0&&quantity<=1000000&&(number(p,"quantity_enabled")==0||quantity>0),"启用件数规则后，请填写1至1000000的总件数阈值");result.put("quantity_threshold",quantity);
        require(p.get("review_skus") instanceof List&&((List<?>)p.get("review_skus")).size()<=200,"指定复核SKU最多200个");List<String> skus=new ArrayList<>();for(Object value:(List<?>)p.get("review_skus")){require(value instanceof String,"复核SKU格式无效");String sku=((String)value).trim();require(!sku.isEmpty()&&sku.length()<=100&&!skus.contains(sku),"复核SKU为空、过长或重复");skus.add(sku);}result.put("review_skus",skus);return result;
    }
    private static void check(List<Map<String,Object>> checks,String code,String title,String result,String detail){Map<String,Object> c=new LinkedHashMap<>();c.put("code",code);c.put("title",title);c.put("result",result);c.put("detail",detail);checks.add(c);}
    @SuppressWarnings("unchecked") public static Map<String,Object> assess(Map<String,Object> policy,Map<String,Object> facts){
        Map<String,Object> p=validate(policy),report=new LinkedHashMap<>();List<Map<String,Object>> checks=new ArrayList<>();List<String> manual=new ArrayList<>();String block=OrderReviewPolicy.systemBlock(facts);
        if(!"WAIT_ALLOCATION".equals(text(facts,"status")))block="业务订单已停止履约";
        if(!"SUCCESS".equals(text(facts,"conversion_status"))||number(facts,"business_order_id")!=number(facts,"id"))block="业务订单关联状态不一致";
        long required=number(facts,"quantity"),reserved=number(facts,"reserved_quantity");BigDecimal payment=null;try{payment=new BigDecimal(text(facts,"payment"));}catch(Exception ignored){}
        if(required<=0||reserved<0||reserved>required||payment==null||payment.signum()<0||number(facts,"invalid_lines")>0)block="订单商品、金额或逻辑预占数量异常，请人工核对";
        if(required==0||facts.containsKey("shipped_quantity")&&required<=number(facts,"shipped_quantity"))block="订单没有剩余可履约商品，无需再审核";
        if(number(facts,"ledger_errors")>0||!text(facts,"stock_error").isEmpty())block="库存来源或预占处理异常，请核对具体原因，不能按普通缺货放行";
        check(checks,"BASE","基础履约条件",block.isEmpty()?"PASS":"BLOCKED",block.isEmpty()?"交易状态、来源版本及订单基础数据符合条件":block);
        boolean automatic="AUTO".equals(text(p,"mode"));check(checks,"MODE","店铺审单方式",automatic?"PASS":"MANUAL",automatic?"按规则自动审核":"店铺配置为人工审核");
        boolean match=number(p,"manual_match_required")==1&&number(facts,"manual_match_count")>0;if(match)manual.add("人工匹配商品需复核");check(checks,"MANUAL_MATCH","人工匹配需复核",number(p,"manual_match_required")==0?"DISABLED":match?"HIT":"PASS",match?"订单包含人工匹配过的商品":"未命中");
        boolean amount=number(p,"amount_enabled")==1&&payment!=null&&payment.compareTo(new BigDecimal(text(p,"amount_threshold")))>=0;if(amount)manual.add("实付金额达到复核阈值");check(checks,"AMOUNT","实付金额达到阈值",number(p,"amount_enabled")==0?"DISABLED":amount?"HIT":"PASS","实付 "+text(facts,"payment")+"；阈值 ≥ "+text(p,"amount_threshold"));
        boolean count=number(p,"quantity_enabled")==1&&required>=number(p,"quantity_threshold");if(count)manual.add("商品总件数达到复核阈值");check(checks,"QUANTITY","商品总件数达到阈值",number(p,"quantity_enabled")==0?"DISABLED":count?"HIT":"PASS","总件数 "+required+"；阈值 ≥ "+number(p,"quantity_threshold"));
        Set<String> orderSkus=new HashSet<>((List<String>)facts.getOrDefault("skus",Collections.emptyList()));List<String> hits=new ArrayList<>();for(String sku:(List<String>)p.get("review_skus"))if(orderSkus.contains(sku))hits.add(sku);if(!hits.isEmpty())manual.add("包含指定复核SKU："+String.join("、",hits));check(checks,"SKU","指定SKU复核",((List<?>)p.get("review_skus")).isEmpty()?"DISABLED":hits.isEmpty()?"PASS":"HIT",hits.isEmpty()?"未命中":String.join("、",hits));
        boolean wait="FULL_RESERVED".equals(text(p,"stock_condition"))&&reserved<required;check(checks,"STOCK","库存审核条件",wait?"WAIT":"PASS","FULL_RESERVED".equals(text(p,"stock_condition"))?"要求整单逻辑预占完成；已预占 "+reserved+" / "+required+" 件":"不限制逻辑预占进度；已预占 "+reserved+" / "+required+" 件");
        String decision=!block.isEmpty()?"BLOCKED":!automatic||!manual.isEmpty()?"MANUAL_REQUIRED":wait?"WAIT_STOCK":"AUTO_APPROVED";
        report.put("decision",decision);report.put("summary",!block.isEmpty()?block:!automatic?"店铺配置为人工审核":!manual.isEmpty()?String.join("；",manual):wait?"待库存满足：已逻辑预占 "+reserved+" / "+required+" 件":"全部审核条件通过，系统自动审核");report.put("checks",checks);report.put("policy",p);report.put("facts",new LinkedHashMap<>(facts));return report;
    }
}
