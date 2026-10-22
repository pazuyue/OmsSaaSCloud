package com.oms.order.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.oms.order.service.GiftRules.*;
import static com.oms.order.service.OrderValues.*;
import static org.junit.jupiter.api.Assertions.*;

class GiftRulesTest {
    static Map<String,Object> gift(String sku,long qty){return map("sku_sn",sku,"quantity",qty,"goods_name","赠品"+sku);}
    static Map<String,Object> rule(String key,String type,long minimum,String amount,String sku){return map("key",key,"name",key,"type",type,"minimum",minimum,"amount",amount,"cap",100,"skus","AMOUNT".equals(type)?Collections.emptyList():Collections.singletonList("A"),"gifts",Collections.singletonList(gift(sku,1)),"tiers",Collections.emptyList(),"stop",false);}
    static Map<String,Object> policy(List<Map<String,Object>> rules){return normalize(map("channels",Collections.singletonList(1),"start_time","2026-10-01 00:00:00","end_time","2026-11-01 00:00:00","cross_group_stack",false,"shipped_policy","RETURN","allow_waive",true,"rules",rules));}
    static Map<String,Object> group(long id,List<Map<String,Object>> rules){Map<String,Object> p=policy(rules);p.put("channels",Collections.singletonList(1));return map("id",id,"name","group"+id,"priority",id,"revision",1,"policy",p);}
    static List<Map<String,Object>> basis(long qty,String amount){return Collections.singletonList(map("platform_oid","101","sku_sn","A","quantity",qty,"payment",amount));}
    static Map<String,Object> run(List<Map<String,Object>> groups,long qty,String amount){return evaluate(groups,1,"2026-10-19 00:00:00",basis(qty,amount));}
    @Test void independentRulesStackAndAccumulateSameSku(){Map<String,Object> p=run(Collections.singletonList(group(1,Arrays.asList(rule("R1","AMOUNT",1,"100","G"),rule("R2","PRODUCT",2,"0","G")))),2,"200");assertEquals(2,number(rows(p.get("gifts")).get(0),"quantity"));assertEquals(2,rows(p.get("entitlements")).size());}
    @Test void unmatchedHighPriorityGroupDoesNotExcludeLower(){Map<String,Object> p=run(Arrays.asList(group(1,Collections.singletonList(rule("R1","AMOUNT",1,"500","X"))),group(2,Collections.singletonList(rule("R2","PRODUCT",1,"0","G")))),2,"200");assertEquals("G",text(rows(p.get("gifts")).get(0),"sku_sn"));}
    @Test void matchingGroupExcludesLaterByDefault(){Map<String,Object> p=run(Arrays.asList(group(1,Collections.singletonList(rule("R1","PRODUCT",1,"0","X"))),group(2,Collections.singletonList(rule("R2","PRODUCT",1,"0","G")))),2,"200");assertEquals(1,rows(p.get("groups")).size());}
    @Test void crossGroupStackIsExplicit(){Map<String,Object> g=group(1,Collections.singletonList(rule("R1","PRODUCT",1,"0","X")));object(g.get("policy"));((Map<String,Object>)g.get("policy")).put("cross_group_stack",true);assertEquals(2,rows(run(Arrays.asList(g,group(2,Collections.singletonList(rule("R2","PRODUCT",1,"0","G")))),2,"200").get("gifts")).size());}
    @Test void stopAppliesOnlyAfterMatch(){Map<String,Object> r=rule("R1","AMOUNT",1,"500","X");r.put("stop",true);Map<String,Object> p=run(Collections.singletonList(group(1,Arrays.asList(r,rule("R2","PRODUCT",1,"0","G")))),2,"200");assertEquals("G",text(rows(p.get("gifts")).get(0),"sku_sn"));}
    @Test void matchedStopEndsCurrentGroup(){Map<String,Object> r=rule("R1","AMOUNT",1,"100","X");r.put("stop",true);assertEquals(1,rows(run(Collections.singletonList(group(1,Arrays.asList(r,rule("R2","PRODUCT",1,"0","G")))),2,"200").get("gifts")).size());}
    @Test void stoppedGroupsRemainVisibleInTrialWithoutGrantingEntitlements(){
        Map<String,Object> result=run(Arrays.asList(group(1,Collections.singletonList(rule("R1","PRODUCT",1,"0","X"))),group(2,Collections.singletonList(rule("R2","PRODUCT",1,"0","G")))),2,"200");
        List<Map<String,Object>> trace=rows(result.get("trace"));assertEquals(2,trace.size());assertEquals(Boolean.TRUE,trace.get(1).get("skipped"));assertTrue(text(trace.get(1),"reason").contains("未执行"));assertTrue(rows(trace.get(1).get("rules")).isEmpty());assertEquals(1,rows(result.get("entitlements")).size());
    }
    @Test void stoppedRulesRemainVisibleWithoutBeingEvaluatedOrGranted(){
        Map<String,Object> first=rule("R1","PRODUCT",1,"0","X");first.put("stop",true);
        Map<String,Object> result=run(Collections.singletonList(group(1,Arrays.asList(first,rule("R2","PRODUCT",1,"0","G")))),2,"200");
        List<Map<String,Object>> trace=rows(rows(result.get("trace")).get(0).get("rules"));assertEquals(2,trace.size());assertEquals(Boolean.TRUE,trace.get(1).get("skipped"));assertFalse(trace.get(1).containsKey("basis_quantity"));assertEquals(1,rows(result.get("entitlements")).size());assertEquals("X",text(rows(result.get("gifts")).get(0),"sku_sn"));
    }
    @Test void buyFourReturnOneOnlyOneGiftSurvives(){Map<String,Object> p=run(Collections.singletonList(group(1,Collections.singletonList(rule("R1","MULTIPLE",2,"0","G")))),4,"400");assertEquals(2,number(rows(p.get("gifts")).get(0),"quantity"));assertEquals(1,number(retained(p,basis(3,"300"),false).get(0),"quantity"));}
    @Test void fullReturnRemovesEntitlement(){Map<String,Object> p=run(Collections.singletonList(group(1,Collections.singletonList(rule("R1","MULTIPLE",2,"0","G")))),4,"400");assertTrue(retained(p,basis(0,"0"),false).isEmpty());}
    @Test void recheckCannotGrantAnInitiallyUnmatchedRule(){Map<String,Object> p=run(Collections.singletonList(group(1,Arrays.asList(rule("R1","AMOUNT",1,"100","G"),rule("R2","AMOUNT",1,"500","X")))),2,"200");assertEquals(1,retained(p,basis(4,"600"),false).size());}
    @Test void retainedNeverExceedsOriginalQuantity(){Map<String,Object> p=run(Collections.singletonList(group(1,Collections.singletonList(rule("R1","MULTIPLE",2,"0","G")))),2,"200");assertEquals(1,number(retained(p,basis(20,"2000"),false).get(0),"quantity"));}
    @Test void keepPolicyOnlyProtectsAlreadyShippedGift(){Map<String,Object> g=group(1,Collections.singletonList(rule("R1","PRODUCT",1,"0","G")));((Map<String,Object>)g.get("policy")).put("shipped_policy","KEEP");Map<String,Object> p=run(Collections.singletonList(g),2,"200");assertTrue(retained(p,basis(0,"0"),false).isEmpty());assertEquals(1,number(retained(p,basis(0,"0"),true).get(0),"quantity"));}
    @Test void separateChainedEntitlementsAreRecheckedIndividually(){Map<String,Object> p=run(Collections.singletonList(group(1,Arrays.asList(rule("R1","AMOUNT",1,"300","G"),rule("R2","PRODUCT",2,"0","X")))),4,"400");List<Map<String,Object>> retained=retained(p,basis(2,"200"),false);assertEquals(1,retained.size());assertEquals("X",text(retained.get(0),"sku_sn"));}
    @Test void tierUsesHighestMatchingTierAndDoesNotSwapToAnotherGiftAfterReturn(){Map<String,Object> r=rule("R1","TIER",1,"0","G");r.put("tiers",Arrays.asList(map("minimum",1,"gifts",Collections.singletonList(gift("X",1))),map("minimum",3,"gifts",Collections.singletonList(gift("G",2)))));Map<String,Object> p=run(Collections.singletonList(group(1,Collections.singletonList(r))),4,"400");assertEquals(2,number(rows(p.get("gifts")).get(0),"quantity"));assertTrue(retained(p,basis(2,"200"),false).isEmpty());}
    @Test void giftCannotTriggerAnotherGift(){List<Map<String,Object>> b=basis(1,"100");b.get(0).put("line_kind","GIFT");assertThrows(IllegalArgumentException.class,()->GiftRules.rule(rule("R1","AMOUNT",1,"100","G"),b));}
    @Test void giftsHaveNoFabricatedPlatformOidAndZeroSaleAmount(){Map<String,Object> g=rows(run(Collections.singletonList(group(1,Collections.singletonList(rule("R1","PRODUCT",1,"0","G")))),1,"100").get("gifts")).get(0);assertEquals("",g.get("platform_oid"));assertEquals(0,decimal(g.get("payment")).signum());}
    @Test void shopAndPaidTimeAreRequiredScope(){Map<String,Object> g=group(1,Collections.singletonList(rule("R1","PRODUCT",1,"0","G")));assertTrue(rows(evaluate(Collections.singletonList(g),2,"2026-10-19 00:00:00",basis(1,"100")).get("gifts")).isEmpty());assertTrue(rows(evaluate(Collections.singletonList(g),1,"2026-12-19 00:00:00",basis(1,"100")).get("gifts")).isEmpty());}
    @Test void duplicateRuleIdentityIsRejected(){assertThrows(IllegalArgumentException.class,()->group(1,Arrays.asList(rule("R1","PRODUCT",1,"0","G"),rule("R1","PRODUCT",1,"0","X"))));}
    @Test void invalidTierOrderingIsRejected(){Map<String,Object> r=rule("R1","TIER",1,"0","G");r.put("tiers",Arrays.asList(map("minimum",3,"gifts",Collections.singletonList(gift("G",1))),map("minimum",2,"gifts",Collections.singletonList(gift("G",2)))));assertThrows(IllegalArgumentException.class,()->group(1,Collections.singletonList(r)));}
    @Test void cumulativeTimesCountSetsRatherThanGiftPieces(){
        Map<String,Object> r=rule("R1","MULTIPLE",3,"0","G");r.put("max_count",2);r.put("gifts",Arrays.asList(gift("G",2),gift("X",3)));Map<String,Object> result=run(Collections.singletonList(group(1,Collections.singletonList(r))),9,"300");
        assertEquals(4,number(rows(result.get("gifts")).get(0),"quantity"));assertEquals(6,number(rows(result.get("gifts")).get(1),"quantity"));Map<String,Object> trace=rows(rows(result.get("trace")).get(0).get("rules")).get(0);assertEquals(3,number(trace,"requested_count"));assertEquals(2,number(trace,"granted_count"));
    }
    @Test void amountProductAndHighestTierEachConsumeOneTime(){
        for(String type:Arrays.asList("AMOUNT","PRODUCT","TIER")){Map<String,Object> r=rule("R1",type,1,"100","G");r.put("gifts",Collections.singletonList(gift("G",5)));if("TIER".equals(type))r.put("tiers",Arrays.asList(map("minimum",1,"gifts",Collections.singletonList(gift("G",2))),map("minimum",3,"gifts",Collections.singletonList(gift("G",5)))));Map<String,Object> result=run(Collections.singletonList(group(1,Collections.singletonList(r))),10,"300");assertEquals(5,number(rows(result.get("gifts")).get(0),"quantity"));assertEquals(1,number(rows(rows(result.get("trace")).get(0).get("rules")).get(0),"requested_count"));}
    }
    @Test void cumulativeLimitRejectsNegativeFractionalAndExcessiveInput(){
        for(Object maximum:Arrays.asList(-1,1.5,1000000001L)){Map<String,Object> r=rule("R1","PRODUCT",1,"0","G");r.put("max_count",maximum);assertThrows(IllegalArgumentException.class,()->group(1,Collections.singletonList(r)));}
    }
}
