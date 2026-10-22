package com.oms.inventory.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.oms.common.lucky.LuckyBagRules.*;

/** Read-only explanations. They never replace the locked allocation planner. */
public final class LuckyBagDiagnostics {
    private LuckyBagDiagnostics() {}

    public static Map<String,Object> describe(Map<String,Object> rule,List<Map<String,Object>> facts,int quantity) {
        Map<String,List<Map<String,Object>>> batches=new HashMap<>();
        for(Map<String,Object> fact:facts) if(number(fact,"available")>0)
            batches.computeIfAbsent(text(fact,"sku_sn"),k->new ArrayList<>()).add(fact);
        List<Map<String,Object>> groups=new ArrayList<>(),issues=new ArrayList<>();
        boolean preset="PRESET".equals(text(rule,"mode"));
        BigDecimal cap=money(rule.get("max_cost")),minimumTotal=BigDecimal.ZERO;
        List<Map<String,Object>> missing=new ArrayList<>();
        for(Map<String,Object> group:rows(rule.get("groups"))) {
            long fixed=0,fixedShortage=0,flexAvailable=0;
            List<Map<String,Object>> candidates=new ArrayList<>(),flexFacts=new ArrayList<>();
            for(Map<String,Object> candidate:rows(group.get("candidates"))) {
                String sku=text(candidate,"sku_sn");List<Map<String,Object>> stock=batches.getOrDefault(sku,Collections.emptyList());
                long available=stock.stream().mapToLong(f->number(f,"available")).sum();
                boolean isFixed=preset&&candidate.get("fixed_quantity")!=null;
                long needed=isFixed?number(candidate,"fixed_quantity"):0,shortage=isFixed?Math.max(0,needed-available):0;
                candidates.add(map("sku_sn",sku,"goods_name",candidate.get("goods_name"),"available",available,"required",isFixed?needed:null,"shortage",isFixed?shortage:null,"mode",isFixed?(needed==0?"EXCLUDE":"FIXED"):"AUTO"));
                if(isFixed) {
                    fixed+=needed;fixedShortage+=shortage;
                    if(shortage>0) issues.add(map("code","STOCK_SHORTAGE","group_key",group.get("key"),"sku_sn",sku,"required",needed,"available",available,"shortage",shortage,"message",sku+"需要"+needed+"件，可用"+available+"件，缺"+shortage+"件"));
                    if(cap.signum()>0&&needed>0) {
                        long known=known(stock);if(known<needed)missing.add(map("sku_sn",sku,"group_name",group.get("name"),"missing_quantity",needed-known));
                        minimumTotal=minimumTotal.add(cheapest(stock,needed));
                    }
                } else {flexAvailable+=available;flexFacts.addAll(stock);}
            }
            long remainder=quantity-fixed,flexShortage=Math.max(0,remainder-flexAvailable);
            if(flexShortage>0)issues.add(map("code","GROUP_STOCK_SHORTAGE","group_key",group.get("key"),"required",remainder,"available",flexAvailable,"shortage",flexShortage,"message",text(group,"name")+"剩余需"+remainder+"件，参与剩余分配的候选可用"+flexAvailable+"件，缺"+flexShortage+"件"));
            if(cap.signum()>0&&remainder>0){long known=known(flexFacts);if(known<remainder)missing.add(map("group_name",group.get("name"),"missing_quantity",remainder-known));minimumTotal=minimumTotal.add(cheapest(flexFacts,remainder));}
            groups.add(map("key",group.get("key"),"name",group.get("name"),"required",quantity,"fixed_required",fixed,"fixed_shortage",fixedShortage,"flexible_required",remainder,"flexible_available",flexAvailable,"flexible_shortage",flexShortage,"shortage",fixedShortage+flexShortage,"candidates",candidates));
        }
        Map<String,Object> result=map("groups",groups,"issues",issues,"stock_sufficient",issues.isEmpty(),"cost_cap",cap);
        if(!issues.isEmpty()) {result.put("code","STOCK_SHORTAGE");result.put("message","本仓无法配齐本批"+quantity+"袋，请查看商品及分组缺口；补货、调整配额或切换仓库后重试");}
        else if(cap.signum()>0&&!missing.isEmpty()) {result.put("code","COST_MISSING");result.put("missing_cost",missing);result.put("message","组件数量充足，但有必需组件缺少可用批次成本，无法校验每袋成本上限，请补齐成本资料");}
        else if(cap.signum()>0&&minimumTotal.compareTo(cap.multiply(BigDecimal.valueOf(quantity)))>0) {
            result.put("code","COST_LIMIT");result.put("minimum_total_cost",minimumTotal);result.put("minimum_average_cost",minimumTotal.divide(BigDecimal.valueOf(quantity),4,RoundingMode.CEILING));
            result.put("message","组件数量充足，但本批最低组件总成本为"+minimumTotal.stripTrailingZeros().toPlainString()+"元，平均每袋至少"+result.get("minimum_average_cost")+"元，超过每袋"+cap.stripTrailingZeros().toPlainString()+"元上限；请调整候选或成本上限");
        } else {result.put("code","STOCK_READY");result.put("message","组件数量满足需求，逐袋组合及成本请以完整试算为准");}
        return result;
    }

    private static long known(List<Map<String,Object>> facts) {return facts.stream().filter(f->f.get("unit_cost")!=null).mapToLong(f->number(f,"available")).sum();}
    private static BigDecimal cheapest(List<Map<String,Object>> facts,long required) {
        List<Map<String,Object>> priced=new ArrayList<>();for(Map<String,Object> f:facts)if(f.get("unit_cost")!=null)priced.add(f);
        priced.sort(Comparator.comparing(f->money(f.get("unit_cost"))));BigDecimal cost=BigDecimal.ZERO;
        for(Map<String,Object> fact:priced){long take=Math.min(required,number(fact,"available"));cost=cost.add(money(fact.get("unit_cost")).multiply(BigDecimal.valueOf(take)));required-=take;if(required==0)break;}return cost;
    }
}
