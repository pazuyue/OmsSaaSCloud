package com.oms.order.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundGiftScopeTest {
    Map<String,Object> refund=m("order_id",1,"platform_oid","OID-K");
    RefundGiftScope scope(boolean shared,Map<String,Object> report){OrderRepository r=mock(OrderRepository.class);JdbcTemplate db=mock(JdbcTemplate.class);when(r.db()).thenReturn(db);when(db.queryForObject(anyString(),eq(Long.class),eq("QM"),eq(1))).thenReturn(shared?2L:1L);return new RefundGiftScope(r,"QM",refund,report);}
    Map<String,Object> report(String type,List<String> skus){return m("effective_basis",Arrays.asList(m("platform_oid","OID-K","sku_sn","KIT-K"),m("platform_oid","OID-L","sku_sn","BAG-L")),"snapshot",m("entitlements",Arrays.asList(m("sku_sn","GIFT-K","rule_key","K","policy",m("rules",Arrays.asList(m("key","K","type",type,"skus",skus)))),m("sku_sn","GIFT-L","rule_key","L","policy",m("rules",Arrays.asList(m("key","L","type","PRODUCT","skus",Arrays.asList("BAG-L"))))))));}
    @Test void matchingGiftParentsOwnOnlyTheirOriginalSalesRule(){RefundGiftScope s=scope(true,report("PRODUCT",Arrays.asList("KIT-K")));assertEquals(RefundGiftScope.Match.OWN,s.match(m("sku_sn","COMPONENT-E","gift_parent_sku","GIFT-K")));assertEquals(RefundGiftScope.Match.OTHER,s.match(m("sku_sn","COMPONENT-G","gift_parent_sku","GIFT-L")));}
    @Test void aSharedAmountPromotionRequiresOwnershipReview(){assertEquals(RefundGiftScope.Match.AMBIGUOUS,scope(true,report("AMOUNT",Collections.emptyList())).match(m("sku_sn","GIFT-K")));}
    @Test void multipleEligibleSalesParentsCannotBeAssignedByExecutionOrder(){assertEquals(RefundGiftScope.Match.AMBIGUOUS,scope(true,report("PRODUCT",Arrays.asList("KIT-K","BAG-L"))).match(m("sku_sn","GIFT-K")));}
    @Test void missingFrozenEvidenceDoesNotGuessOwnership(){assertEquals(RefundGiftScope.Match.AMBIGUOUS,scope(true,m()).match(m("sku_sn","GIFT-K")));}
    @Test void aSingleCancelRefundRetainsExistingOrderGiftRecovery(){assertEquals(RefundGiftScope.Match.OWN,scope(false,m()).match(m("sku_sn","GIFT-K")));}
}
