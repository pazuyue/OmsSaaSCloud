package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.GiftRules.*;
import static com.oms.order.service.OrderValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GiftCatalogTest {
    GiftQuotaTest f;
    @BeforeAll static void start(){GiftQuotaTest.start();}
    @BeforeEach void setup(){f=new GiftQuotaTest();f.setup();}
    @AfterEach void finish(){f.finish();}
    @Test void strategyEnableProtectsActualGiftSkusBeforeQuotaConfigurationAndRefreshesAfterCommit(){
        long id=f.save(f.limited("R","PRODUCT",10,"G"));Map<String,Object> g=f.group(id);String token=text(object(g.get("policy")),"catalog_token");assertTrue(token.matches("[a-f0-9]{32}"));verify(f.gifts.catalog).protectGift(eq("QUOTA"),eq(id),eq(text(g,"name")),eq(token),eq(Collections.singleton("G")));verify(f.gifts.catalog).refreshGift("QUOTA",id);
    }
    @Test void failedProtectionCannotCommitAnEnabledStrategyOrQuota(){
        doThrow(new IllegalArgumentException("商品未勾选赠品")).when(f.gifts.catalog).protectGift(anyString(),anyLong(),anyString(),anyString(),anyCollection());assertThrows(IllegalArgumentException.class,()->f.save(f.limited("R","PRODUCT",10,"G")));assertTrue(f.gifts.groups("QUOTA").isEmpty());assertEquals(0,f.count("oms_gift_quota"));verify(f.gifts.catalog).refreshGift(eq("QUOTA"),anyLong());
    }
    @Test void saveRejectsUntaggedGiftButAllowsUntaggedPurchaseConditions(){
        when(f.gifts.catalog.goods(eq("QUOTA"),anyList())).thenAnswer(call->{Map<String,List<Map<String,Object>>> found=new HashMap<>();for(String sku:(List<String>)call.getArgument(1))found.put(sku,Collections.singletonList(map("sku_sn",sku,"is_gift",sku.equals("G")?1:0,"is_fd",0,"is_package",0)));return found;});
        assertTrue(f.save(f.limited("R","PRODUCT",0,"G"))>0);assertThrows(IllegalArgumentException.class,()->f.save(f.limited("R","PRODUCT",0,"X")));
    }
    @Test void referenceSnapshotIncludesOnlyEnabledActualGiftSkusAcrossAllTiers(){
        Map<String,Object> tier=f.limited("R","TIER",0,"UNUSED");tier.put("tiers",Arrays.asList(map("minimum",1,"gifts",Collections.singletonList(GiftRulesTest.gift("G",1))),map("minimum",3,"gifts",Collections.singletonList(GiftRulesTest.gift("X",2)))));long id=f.save(tier);
        Map<String,Object> report=f.gifts.catalogReferences("QUOTA",map("group_ids",Arrays.asList(id,999999999L),"skus",Arrays.asList("A","G","X","UNUSED")));assertEquals(2,rows(report.get("references")).size());assertEquals(2,rows(report.get("states")).size());assertEquals(0,number(rows(report.get("states")).get(1),"enabled"));
        Map<String,Object> g=f.group(id);g.put("enabled",0);f.gifts.save("QUOTA",g,"test");assertTrue(rows(f.gifts.catalogReferences("QUOTA",map("group_ids",Collections.singletonList(id),"skus",Arrays.asList("G","X"))).get("references")).isEmpty());assertTrue(rows(f.gifts.catalogReferences("OTHER",map("group_ids",Collections.singletonList(id),"skus",Collections.singletonList("G"))).get("references")).isEmpty());
    }
    @Test void referenceCleanupFailureDoesNotUndoSuccessfulStrategySave(){
        doThrow(new IllegalStateException("offline")).when(f.gifts.catalog).refreshGift(anyString(),anyLong());long id=f.save(f.limited("R","PRODUCT",10,"G"));assertEquals(1,number(f.group(id),"enabled"));assertEquals(0,f.used(id));
    }
}
