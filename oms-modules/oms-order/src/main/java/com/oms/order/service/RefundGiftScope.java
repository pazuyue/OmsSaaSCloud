package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Independent product promotions keep their original sales-parent ownership across concurrent refunds. */
final class RefundGiftScope {
    enum Match { OWN, OTHER, AMBIGUOUS }
    private final Map<String,Object> report,refund;
    private final boolean shared;
    private final Set<String> current=new HashSet<>();
    private final Map<String,Match> cached=new HashMap<>();
    RefundGiftScope(OrderRepository r,String c,Map<String,Object> b,Map<String,Object> report){
        this.report=report;this.refund=b;
        shared=r.db().queryForObject("SELECT COUNT(*) FROM oms_refund WHERE company_code=? AND order_id=? AND review_state='APPROVED' AND purpose='CANCEL' AND state NOT IN ('CLOSED','CANCELED')",Long.class,c,b.get("order_id"))>1;
        for(Map<String,Object> basis:RefundDispositionService.rows(report.get("effective_basis")))if(text(refund,"platform_oid").equals(text(basis,"platform_oid")))current.add(text(basis,"sku_sn"));
    }
    Match match(Map<String,Object> line){
        if(!shared)return Match.OWN;
        String parent=text(line,"gift_parent_sku");if(parent.isEmpty())parent=text(line,"sku_sn");return cached.computeIfAbsent(parent,this::resolve);
    }
    private Match resolve(String parent){
        if(current.isEmpty())return Match.AMBIGUOUS;
        boolean seen=false,own=false;
        for(Map<String,Object> entitlement:RefundDispositionService.rows(obj(report.get("snapshot")).get("entitlements"))){
            if(!parent.equals(text(entitlement,"sku_sn")))continue;seen=true;Map<String,Object> rule=null;
            for(Map<String,Object> candidate:RefundDispositionService.rows(obj(entitlement.get("policy")).get("rules")))if(text(entitlement,"rule_key").equals(text(candidate,"key")))rule=candidate;
            if(rule==null||!"PRODUCT".equals(text(rule,"type"))||!(rule.get("skus") instanceof Collection))return Match.AMBIGUOUS;
            Set<String> scope=new HashSet<>();for(Object sku:(Collection<?>)rule.get("skus"))scope.add(String.valueOf(sku));
            if(scope.size()!=1)return Match.AMBIGUOUS;
            own|=scope.stream().anyMatch(current::contains);
        }
        return !seen?Match.AMBIGUOUS:own?Match.OWN:Match.OTHER;
    }
}
