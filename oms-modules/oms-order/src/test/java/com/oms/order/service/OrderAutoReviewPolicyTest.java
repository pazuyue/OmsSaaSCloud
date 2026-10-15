package com.oms.order.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.order.service.OrderValues.*;
class OrderAutoReviewPolicyTest {
    Map<String,Object> p(){Map<String,Object> p=OrderAutoReviewPolicy.defaults();p.put("mode","AUTO");return p;}
    Map<String,Object> f(long reserved){Map<String,Object> f=new HashMap<>();f.put("id",1);f.put("business_order_id",1);f.put("status","WAIT_ALLOCATION");f.put("conversion_status","SUCCESS");f.put("platform_status","WAIT_SELLER_SEND_GOODS");f.put("payment","100.00");f.put("quantity",10);f.put("reserved_quantity",reserved);f.put("skus",Arrays.asList("A"));return f;}
    String decision(Map<String,Object> p,Map<String,Object> f){return text(OrderAutoReviewPolicy.assess(p,f),"decision");}
    @Test void defaultIsManual(){assertEquals("MANUAL_REQUIRED",decision(OrderAutoReviewPolicy.defaults(),f(10)));}
    @Test void unrestrictedStockCanApproveWithNoLogicalReservation(){assertEquals("AUTO_APPROVED",decision(p(),f(0)));}
    @Test void fullStockModeWaitsUntilEntireOrderIsReserved(){Map<String,Object> p=p();p.put("stock_condition","FULL_RESERVED");assertEquals("WAIT_STOCK",decision(p,f(6)));assertEquals("AUTO_APPROVED",decision(p,f(10)));}
    @Test void zeroChannelQuotaDoesNotInvalidateCompletedPublicStockReservation(){Map<String,Object> p=p(),f=f(10);p.put("stock_condition","FULL_RESERVED");f.put("channel_available",0);f.put("public_reserved",10);assertEquals("AUTO_APPROVED",decision(p,f));}
    @Test void manualConditionsWinOverStockWaitAndEveryHitIsRecorded(){Map<String,Object> p=p(),f=f(0);p.put("stock_condition","FULL_RESERVED");p.put("amount_enabled",1);p.put("amount_threshold","100.00");p.put("quantity_enabled",1);p.put("quantity_threshold",10);p.put("review_skus",Arrays.asList("A"));f.put("manual_match_count",1);Map<String,Object> result=OrderAutoReviewPolicy.assess(p,f);assertEquals("MANUAL_REQUIRED",result.get("decision"));assertEquals(4,((List<Map<String,Object>>)result.get("checks")).stream().filter(c->"HIT".equals(c.get("result"))).count());}
    @Test void moneyUsesExactDecimalAndInclusiveThreshold(){Map<String,Object> p=p(),f=f(10);p.put("amount_enabled",1);p.put("amount_threshold","100.01");assertEquals("AUTO_APPROVED",decision(p,f));f.put("payment","100.01");assertEquals("MANUAL_REQUIRED",decision(p,f));}
    @Test void artificialSkuIsNotInventedAndSkuMatchingIsExact(){Map<String,Object> p=p();p.put("review_skus",Arrays.asList("a"));assertEquals("AUTO_APPROVED",decision(p,f(10)));p.put("review_skus",Arrays.asList("A"));assertEquals("MANUAL_REQUIRED",decision(p,f(10)));}
    @Test void manualMatchSwitchIsIndependent(){Map<String,Object> p=p(),f=f(10);f.put("manual_match_count",1);assertEquals("MANUAL_REQUIRED",decision(p,f));p.put("manual_match_required",0);assertEquals("AUTO_APPROVED",decision(p,f));}
    @Test void platformAndLedgerBlocksCannotBeOverriddenByUnrestrictedStock(){Map<String,Object> f=f(10);f.put("fulfillment_block_reason","退款中");assertEquals("BLOCKED",decision(p(),f));f.remove("fulfillment_block_reason");f.put("ledger_errors",1);assertEquals("BLOCKED",decision(p(),f));}
    @Test void invalidOrderFactsFailClosed(){for(String field:Arrays.asList("payment","quantity","reserved_quantity")){Map<String,Object> f=f(10);f.put(field,field.equals("payment")?"invalid":-1);assertEquals("BLOCKED",decision(p(),f));}}
    @Test void invalidEnabledThresholdsAndStockModeAreRejected(){Map<String,Object> p=p();p.put("amount_enabled",1);assertThrows(IllegalArgumentException.class,()->OrderAutoReviewPolicy.validate(p));p.put("amount_threshold","1.001");assertThrows(IllegalArgumentException.class,()->OrderAutoReviewPolicy.validate(p));p.put("amount_enabled",0);p.put("amount_threshold","0");p.put("quantity_enabled",1);assertThrows(IllegalArgumentException.class,()->OrderAutoReviewPolicy.validate(p));p.put("quantity_enabled",0);p.put("stock_condition","UNKNOWN");assertThrows(IllegalArgumentException.class,()->OrderAutoReviewPolicy.validate(p));}
}
