package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.GiftRules.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GiftConfigurationTest {
    RefundServiceTest f;GiftConfiguration config;GiftService gifts;long a,b;
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){f=new RefundServiceTest();f.reset();clear();config=new GiftConfiguration();config.repository=f.repo;config.catalog=mock(OrderCatalog.class);
        when(config.catalog.channels("CONFIG")).thenReturn(Arrays.asList(map("channel_id",1,"enabled",1),map("channel_id",2,"enabled",1),map("channel_id",3,"enabled",0)));
        gifts=new GiftService();gifts.repository=f.repo;gifts.configuration=config;
        a=strategy("A","X",1);b=strategy("B","Y",1);
    }
    void clear(){for(String t:Arrays.asList("oms_gift_shop_member","oms_gift_shop_group_version","oms_gift_shop_group","oms_gift_config_lock","oms_gift_group_version","oms_gift_group"))f.db.update("DELETE FROM "+t+" WHERE company_code='CONFIG'");}
    @AfterEach void finish(){clear();f.clear();}
    long strategy(String name,String sku,int enabled){return f.repo.insert("INSERT INTO oms_gift_group(company_code,name,priority,enabled,policy,updated_by) VALUES ('CONFIG',?,1,?,?,'test')",name,enabled,encode(GiftRulesTest.policy(Collections.singletonList(GiftRulesTest.rule(name,"PRODUCT",1,"0",sku)))));}
    Map<String,Object> body(String name,long shop,Map<String,Object>... links){return map("name",name,"enabled",1,"channels",Collections.singletonList(shop),"links",Arrays.asList(links));}
    Map<String,Object> link(long id,boolean next){return map("group_id",id,"continue_after_match",next);}
    Map<String,Object> save(Map<String,Object> body){return config.save("CONFIG",body,"test");}
    Map<String,Object> run(long channel){return evaluate(gifts.published("CONFIG"),channel,"2026-10-19 00:00:00",GiftRulesTest.basis(2,"200"));}
    List<Map<String,Object>> strategies(){return gifts.groups("CONFIG");}
    @Test void reusableStrategiesHaveIndependentOrderAndContinuationPerShopGroup(){save(body("店铺组一",1,link(a,true),link(b,false)));save(body("店铺组二",2,link(b,false),link(a,true)));assertEquals(2,rows(run(1).get("gifts")).size());assertEquals("Y",text(rows(run(2).get("gifts")).get(0),"sku_sn"));assertEquals(1,rows(run(2).get("gifts")).size());assertEquals("店铺组一",text(rows(run(1).get("groups")).get(0),"shop_group_name"));assertFalse(object(strategies().get(0).get("policy")).containsKey("channels"));}
    @Test void overlappingEnabledShopGroupsRollbackEntireSave(){save(body("第一组",1,link(a,false)));IllegalArgumentException e=assertThrows(IllegalArgumentException.class,()->save(body("第二组",1,link(b,false))));assertTrue(e.getMessage().contains("第一组"));assertEquals(1,config.scopes("CONFIG").size());assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM oms_gift_shop_group_version WHERE company_code='CONFIG'",Integer.class));}
    @Test void disabledGroupsCanBePreparedButCannotBeEnabledWithConflicts(){save(body("第一组",1,link(a,false)));Map<String,Object> draft=body("草稿",1,link(b,false));draft.put("enabled",0);save(draft);Map<String,Object> saved=config.scopes("CONFIG").get(1);saved.put("enabled",1);assertThrows(IllegalArgumentException.class,()->save(saved));assertEquals(1,rows(run(1).get("groups")).size());}
    @Test void membershipCanMoveAfterRemovingFromOriginalEnabledGroup(){save(body("原组",1,link(a,false)));Map<String,Object> first=config.scopes("CONFIG").get(0);first.put("channels",Collections.singletonList(2));save(first);save(body("新组",1,link(b,false)));assertEquals("新组",text(rows(run(1).get("groups")).get(0),"shop_group_name"));}
    @Test void staleRevisionDoesNotOverwriteMembershipOrOrdering(){save(body("第一组",1,link(a,false)));Map<String,Object> old=config.scopes("CONFIG").get(0),current=new LinkedHashMap<>(old);current.put("channels",Collections.singletonList(2));save(current);old.put("name","过期编辑");assertThrows(IllegalArgumentException.class,()->save(old));assertEquals(2,number(config.effective("CONFIG",2,strategies()),"channel_id"));assertTrue(rows(config.effective("CONFIG",1,strategies()).get("shop_groups")).isEmpty());}
    @Test void rejectsDuplicateLinksAndForeignStrategyOrShop(){assertThrows(IllegalArgumentException.class,()->save(body("重复",1,link(a,false),link(a,true))));assertThrows(IllegalArgumentException.class,()->save(body("外部策略",1,link(999999,false))));assertThrows(IllegalArgumentException.class,()->save(body("外部店铺",999999,link(a,false))));assertThrows(IllegalArgumentException.class,()->save(body("停用店铺",3,link(a,false))));assertTrue(config.scopes("CONFIG").isEmpty());}
    @Test void disabledStrategyIsSkippedWithoutStoppingFollowingGroups(){f.db.update("UPDATE oms_gift_group SET enabled=0 WHERE id=?",a);save(body("第一组",1,link(a,false),link(b,false)));assertEquals("Y",text(rows(run(1).get("gifts")).get(0),"sku_sn"));Map<String,Object> scope=rows(config.effective("CONFIG",1,strategies()).get("shop_groups")).get(0);assertEquals(2,rows(scope.get("executions")).size());assertEquals(2,number(rows(run(1).get("groups")).get(0),"priority"));}
    @Test void savedOrderSnapshotKeepsOriginalShopGroupAndRulesAfterConfigEdit(){save(body("原始名称",1,link(a,false)));Map<String,Object> snapshot=run(1),scope=config.scopes("CONFIG").get(0);scope.put("name","新名称");scope.put("links",Collections.singletonList(link(b,false)));save(scope);gifts.invalidate("CONFIG");assertEquals("Y",text(rows(run(1).get("gifts")).get(0),"sku_sn"));assertEquals("X",text(retained(snapshot,GiftRulesTest.basis(2,"200"),false).get(0),"sku_sn"));assertEquals("原始名称",text(rows(snapshot.get("groups")).get(0),"shop_group_name"));}
    @Test void noEnabledMembershipMeansNoPromotionEvenWithEnabledStrategy(){assertTrue(rows(run(1).get("gifts")).isEmpty());assertTrue(rows(config.effective("CONFIG",1,strategies()).get("shop_groups")).isEmpty());}
    @Test void concurrentMembershipCreationOnlyOneWins() throws Exception {ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);try{List<Future<Boolean>> futures=new ArrayList<>();for(int i=0;i<2;i++){final int n=i;futures.add(pool.submit(()->{ready.countDown();go.await();try{save(body("并发"+n,1,link(a,false)));return true;}catch(IllegalArgumentException e){return false;}}));}assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();int wins=0;for(Future<Boolean> future:futures)if(future.get(15,TimeUnit.SECONDS))wins++;assertEquals(1,wins);assertEquals(1,config.scopes("CONFIG").size());}finally{pool.shutdownNow();}}
}
