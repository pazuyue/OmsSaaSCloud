package com.oms.goods;

import com.oms.goods.service.goods.impl.GoodsGiftProtection;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static com.oms.goods.GoodsWorkspaceTest.data;
import static org.junit.jupiter.api.Assertions.*;

/** Real goods transactions; the order inspection callback models committed and in-flight configurations. */
class GoodsGiftProtectionTest {
    GoodsWorkspaceTest f;GoodsGiftProtection protection;long product;
    String token="0123456789abcdef0123456789abcdef";
    @BeforeAll static void start(){GoodsWorkspaceTest.init();}
    @AfterAll static void stop(){GoodsWorkspaceTest.close();}
    @BeforeEach void setup(){f=new GoodsWorkspaceTest();f.seed();protection=f.context.getBean(GoodsGiftProtection.class);Map<String,Object> input=f.product("G");input.put("isGift",1);product=f.goods.saveGoods("QM",input,"test");}
    Map<String,Object> claim(long group,String sku,String token){return data("group_id",group,"group_name","活动"+group,"request_token",token,"skus",Collections.singletonList(sku));}
    Map<String,Object> edit(int flag){Map<String,Object> input=f.product("G");input.put("id",product);input.put("isGift",flag);return input;}
    void live(long group,String token){f.giftInspection=query->{List<Map<String,Object>> states=new ArrayList<>();for(Object id:(List<?>)query.get("group_ids"))states.add(data("group_id",id,"enabled",Long.parseLong(String.valueOf(id))==group?1:0,"request_token",token));return data("states",states,"references",Collections.emptyList());};}
    long holds(){return f.db.queryForObject("SELECT COUNT(*) FROM goods_gift_strategy_ref",Long.class);}
    @Test void giftSearchExcludesOrdinaryLuckyBagAndVirtualKitButPurchaseSearchIncludesThem(){
        f.goods.saveGoods("QM",f.product("A"),"test");Map<String,Object> bag=f.product("BAG");bag.putAll(data("isGift",1,"isFd",1));f.goods.saveGoods("QM",bag,"test");Map<String,Object> kit=f.product("KIT");kit.putAll(data("isGift",1,"isPackage",1));f.goods.saveGoods("QM",kit,"test");
        assertEquals(1,f.goods.goodsPage("QM",data("giftEligible",true),1,10).getTotal());assertEquals(4,f.goods.goodsPage("QM",Collections.emptyMap(),1,10).getTotal());
    }
    @Test void publishedVirtualKitsAndLuckyBagsCanBeSelectedAndProtectedAsGifts(){f.goods.saveGoods("QM",f.product("A"),"test");Map<String,Object> k=f.product("KIT");k.putAll(data("isGift",1,"isPackage",1));long kit=f.goods.saveGoods("QM",k,"test");Map<String,Object> b=f.product("BAG");b.putAll(data("isGift",1,"isFd",1));long bag=f.goods.saveGoods("QM",b,"test");f.context.getBean(com.oms.goods.service.goods.impl.GoodsBundleService.class).save("QM",kit,data("revision",0,"enabled",1,"inventory_mode","VIRTUAL","components",Collections.singletonList(data("sku_sn","A","quantity",2))),"test");f.context.getBean(com.oms.goods.service.goods.impl.GoodsLuckyBagService.class).save("QM",bag,data("revision",0,"draft_revision",0,"enabled",1,"mode","RANDOM","selection","WEIGHTED","preset_quantity",0,"max_cost",0,"groups",Collections.singletonList(data("key","A","name","上装","candidates",Collections.singletonList(data("sku_sn","A","weight",1))))),"test",true);assertEquals(3,f.goods.goodsPage("QM",data("giftEligible",true),1,10).getTotal());assertTrue(protection.protect("QM",claim(1,"KIT",token)));assertTrue(protection.protect("QM",claim(2,"BAG",token)));Map<String,Object> detail=f.goods.goodsDetail("QM",bag);assertEquals("LUCKY_BAG",detail.get("productType"));assertEquals(1,((Number)detail.get("compositionEnabled")).intValue());assertEquals("VIRTUAL",f.goods.goodsDetail("QM",kit).get("inventoryMode"));}
    @Test void protectionRequiresTaggedPhysicalSkuAndOwnedCompany(){
        f.goods.saveGoods("QM",f.product("A"),"test");assertThrows(IllegalArgumentException.class,()->protection.protect("QM",claim(1,"A",token)));assertThrows(IllegalArgumentException.class,()->protection.protect("OTHER",claim(1,"G",token)));assertEquals(0,holds());assertTrue(protection.protect("QM",claim(1,"G",token)));assertTrue(protection.protect("QM",claim(1,"G",token)));assertEquals(1,holds());
    }
    @Test void liveStrategyPreventsRemovingTagDeletingOrTurningIntoLuckyBag(){
        live(1,token);protection.protect("QM",claim(1,"G",token));IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",edit(0),"test"));assertTrue(error.getMessage().contains("活动1"));assertThrows(IllegalArgumentException.class,()->f.goods.deleteGoods("QM",Collections.singletonList(product)));Map<String,Object> bag=edit(1);bag.put("isFd",1);assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",bag,"test"));assertEquals(1,((Number)f.goods.goodsDetail("QM",product).get("isGift")).intValue());
    }
    @Test void currentEnabledConfigurationIsProtectedEvenWithoutAnExistingProtectionRow(){
        f.giftInspection=query->data("states",Collections.emptyList(),"references",Collections.singletonList(data("group_id",9,"group_name","当前启用策略","sku_sn","G")));assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",edit(0),"test"));assertEquals("当前启用策略",f.goods.giftReferences("QM",product).get(0).get("group_name"));assertEquals(0,holds());
    }
    @Test void disabledOrRolledBackStrategyAllowsTagRemovalAfterExactTokenCleanup(){
        protection.protect("QM",claim(1,"G",token));f.goods.saveGoods("QM",edit(0),"test");assertEquals(0,holds());assertEquals(0,((Number)f.goods.goodsDetail("QM",product).get("isGift")).intValue());
    }
    @Test void cleanupOfOldVersionCannotEraseNewVersionProtection(){
        String next="abcdef0123456789abcdef0123456789";protection.protect("QM",claim(1,"G",token));protection.protect("QM",claim(1,"G",next));live(1,next);protection.refresh("QM",1);assertEquals(1,holds());assertEquals(next,f.db.queryForObject("SELECT request_token FROM goods_gift_strategy_ref",String.class));assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",edit(0),"test"));
    }
    @Test void ordinaryEditsOnTaggedGoodsStayAvailableWhenReferenceServiceIsOffline(){
        f.giftInspection=query->{throw new IllegalStateException("offline");};Map<String,Object> rename=edit(1);rename.put("goodsName","新名称");f.goods.saveGoods("QM",rename,"test");assertEquals("新名称",f.goods.goodsDetail("QM",product).get("goodsName"));assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",edit(0),"test"));assertEquals(1,((Number)f.goods.goodsDetail("QM",product).get("isGift")).intValue());
    }
    @Test void incompleteInspectionCannotReleasePendingProtection(){
        protection.protect("QM",claim(1,"G",token));f.giftInspection=query->data("states",Collections.emptyList(),"references",Collections.emptyList());assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",edit(0),"test"));assertEquals(1,holds());
    }
    @Test void pendingEnableArrivingAfterInspectionIsStillProtectedByCurrentMasterLockRead() throws Exception {
        CountDownLatch inspected=new CountDownLatch(1),resume=new CountDownLatch(1);f.giftInspection=query->{inspected.countDown();try{assertTrue(resume.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return data("states",Collections.emptyList(),"references",Collections.emptyList());};
        ExecutorService pool=Executors.newSingleThreadExecutor();try{Future<?> removal=pool.submit(()->assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",edit(0),"test")));assertTrue(inspected.await(5,TimeUnit.SECONDS));protection.protect("QM",claim(1,"G",token));resume.countDown();removal.get(10,TimeUnit.SECONDS);assertEquals(1,holds());assertEquals(1,((Number)f.goods.goodsDetail("QM",product).get("isGift")).intValue());}finally{resume.countDown();pool.shutdownNow();}
    }
    @Test void racingTagRemovalAndEnableCanNeverBothSucceed() throws Exception {
        live(1,token);ExecutorService pool=Executors.newFixedThreadPool(2);try{for(int n=0;n<12;n++){
            f.db.update("DELETE FROM goods_gift_strategy_ref");f.db.update("UPDATE goods_sku_sn_info SET is_gift=1 WHERE id=?",product);CountDownLatch go=new CountDownLatch(1);
            Future<Boolean> enable=pool.submit(()->{go.await();try{return protection.protect("QM",claim(1,"G",token));}catch(IllegalArgumentException e){return false;}});
            Future<Boolean> removal=pool.submit(()->{go.await();try{f.goods.saveGoods("QM",edit(0),"test");return true;}catch(IllegalArgumentException e){return false;}});go.countDown();assertNotEquals(enable.get(15,TimeUnit.SECONDS),removal.get(15,TimeUnit.SECONDS));
        }}finally{pool.shutdownNow();}
    }
    @Test void referencesAreCompanyScopedAndIncludeCurrentStrategyNames(){
        live(1,token);protection.protect("QM",claim(1,"G",token));assertEquals("活动1",f.goods.giftReferences("QM",product).get(0).get("group_name"));assertThrows(IllegalArgumentException.class,()->f.goods.giftReferences("OTHER",product));
    }
}
