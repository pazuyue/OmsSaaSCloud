package com.oms.goods;
import org.junit.jupiter.api.*;
import com.oms.goods.service.goods.impl.GoodsBundleService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GoodsBundleTest {
 GoodsWorkspaceTest f;GoodsBundleService service;long parent;
 @BeforeAll static void start(){GoodsWorkspaceTest.init();}
 @AfterAll static void stop(){GoodsWorkspaceTest.close();}
 @BeforeEach void setup(){f=new GoodsWorkspaceTest();f.seed();service=f.context.getBean(GoodsBundleService.class);f.goods.saveGoods("QM",f.product("A"),"test");f.goods.saveGoods("QM",f.product("B"),"test");Map<String,Object> g=f.product("S");g.put("isPackage",1);parent=f.goods.saveGoods("QM",g,"test");}
 Map<String,Object> input(){return f.data("revision",0,"enabled",1,"components",Arrays.asList(f.data("sku_sn","A","quantity",2),f.data("sku_sn","B","quantity",1)));}
 @Test void publishVersionsAreFencedAndOldVersionsRemainImmutable(){service.save("QM",parent,input(),"test");assertThrows(IllegalArgumentException.class,()->service.save("QM",parent,input(),"stale"));Map<String,Object> next=input();next.put("revision",1);service.save("QM",parent,next,"test");assertEquals(2,((Number)service.detail("QM",parent).get("revision")).intValue());assertEquals(2,f.db.queryForObject("SELECT COUNT(*) FROM goods_bundle_version",Integer.class));assertThrows(IllegalArgumentException.class,()->service.detail("OTHER",parent));}
 @Test void noSelfReferenceDuplicateOrNestedKits(){Map<String,Object> i=input();i.put("components",Arrays.asList(f.data("sku_sn","S","quantity",1)));assertThrows(IllegalArgumentException.class,()->service.save("QM",parent,i,"test"));i.put("components",Arrays.asList(f.data("sku_sn","A","quantity",1),f.data("sku_sn","A","quantity",2)));assertThrows(IllegalArgumentException.class,()->service.save("QM",parent,i,"test"));Map<String,Object> nested=f.product("N");nested.put("isPackage",1);f.goods.saveGoods("QM",nested,"test");i.put("components",Arrays.asList(f.data("sku_sn","N","quantity",1)));assertThrows(IllegalArgumentException.class,()->service.save("QM",parent,i,"test"));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM goods_bundle",Integer.class));}
 @Test void referencedComponentCannotBecomeKitAndPublishedParentCannotBecomeStock(){service.save("QM",parent,input(),"test");long child=f.db.queryForObject("SELECT id FROM goods_sku_sn_info WHERE sku_sn='A'",Long.class);Map<String,Object> change=f.product("A");change.put("id",child);change.put("isPackage",1);assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",change,"test"));Map<String,Object> normal=f.product("S");normal.put("id",parent);assertThrows(IllegalArgumentException.class,()->f.goods.saveGoods("QM",normal,"test"));assertThrows(IllegalArgumentException.class,()->f.goods.deleteGoods("QM",Arrays.asList(child)));}
}
