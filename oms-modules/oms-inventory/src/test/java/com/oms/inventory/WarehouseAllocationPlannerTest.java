package com.oms.inventory;
import com.oms.inventory.service.impl.WarehouseAllocationPlanner;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WarehouseAllocationPlannerTest {
    Map<String,Object> row(Object...pairs){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)r.put(pairs[i].toString(),pairs[i+1]);return r;}
    Map<String,Object> source(long id,String sku,long qty){return row("id",id,"demand_id",id,"sku_sn",sku,"quantity",qty,"source_type","PUBLIC_EXTRA");}
    Map<String,Object> store(String code,String real){return row("store_code",code,"owner_code","O","real_store_code",real);}
    Map<String,Object> batch(long id,String code,String sku,long qty){return row("id",id,"store_code",code,"sku_sn",sku,"available",qty);}
    @Test void completeSingleWarehouseBeatsPriorityWarehouseRequiringSplit(){List<Map<String,Object>> p=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",10)),Arrays.asList(store("V1","R1"),store("V2","R2")),Collections.emptyList(),10,true,true,true);assertEquals("V2",p.get(0).get("store_code"));assertEquals(1,p.size());}
    @Test void samePhysicalWarehouseCannotBypassVirtualWarehouseSwitches(){
        List<Map<String,Object>> sources=Arrays.asList(source(1,"A",10)),batches=Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",4)),stores=Arrays.asList(store("V1","R1"),store("V2","R1"));
        assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10,false,false,false));
        assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10,true,false,false));
        List<Map<String,Object>> plan=WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10,true,false,true);
        assertEquals(2,plan.size());assertEquals("V1",plan.get(0).get("store_code"));assertEquals(6L,plan.get(0).get("quantity"));assertEquals("V2",plan.get(1).get("store_code"));assertEquals(4L,plan.get(1).get("quantity"));
    }
    @Test void oneCompleteVirtualWarehouseWinsOverCombiningSamePhysicalWarehouse(){
        List<Map<String,Object>> plan=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",10)),Arrays.asList(store("V1","R1"),store("V2","R1")),Collections.emptyList(),10,true,false,true);
        assertEquals(1,plan.size());assertEquals("V2",plan.get(0).get("store_code"));assertEquals(10L,plan.get(0).get("quantity"));
    }
    @Test void partialSkuCannotMoveToSiblingVirtualWarehouseOnSupplement(){
        List<Map<String,Object>> stores=Arrays.asList(store("V1","R1"),store("V2","R1"));
        List<Map<String,Object>> first=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",4)),stores,Collections.emptyList(),10,true,true,false);
        assertEquals(1,first.size());assertEquals("V1",first.get(0).get("store_code"));assertEquals(6L,first.get(0).get("quantity"));
        assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",4)),Arrays.asList(batch(2,"V2","A",4)),stores,first,10,true,true,false));
        List<Map<String,Object>> next=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",4)),Arrays.asList(batch(1,"V1","A",4),batch(2,"V2","A",4)),stores,first,10,true,true,false);
        assertEquals(1,next.size());assertEquals("V1",next.get(0).get("store_code"));
    }
    @Test void inheritedLocksKeepTheirVirtualWarehouseAndObeySplitPolicy(){
        Map<String,Object> locked=source(1,"A",6);locked.put("source_type","LOCK");locked.put("store_code","V1");locked.put("batch_id",1);
        List<Map<String,Object>> sources=Arrays.asList(locked,source(2,"A",4)),stores=Arrays.asList(store("V1","R1"),store("V2","R1")),batches=Arrays.asList(batch(2,"V2","A",4));
        assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10,true,false,false));
        List<Map<String,Object>> plan=WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10,true,false,true);
        assertEquals("LOCK",plan.get(0).get("source_type"));assertEquals("V1",plan.get(0).get("store_code"));assertEquals("V2",plan.get(1).get("store_code"));
    }
    @Test void differentSkusMayUseDifferentVirtualWarehousesWithoutSplittingEachSku(){
        List<Map<String,Object>> sources=Arrays.asList(source(1,"A",6),source(2,"B",4)),stores=Arrays.asList(store("V1","R1"),store("V2","R1")),batches=Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","B",4));
        assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10,false,false,false));
        assertEquals(2,WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10,true,false,false).size());
    }

    @Test void skuLinesAreGroupedBeforeChoosingWarehouse(){List<Map<String,Object>> p=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",6),source(2,"A",6)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",12)),Arrays.asList(store("V1","R1"),store("V2","R2")),Collections.emptyList(),12,true,false,false);assertTrue(p.stream().allMatch(r->"R2".equals(r.get("real_store_code"))));}
    @Test void failedWholeOrderPlanDoesNotConsumeCallerCapacity(){List<Map<String,Object>> b=Arrays.asList(batch(1,"V1","A",6));assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),b,Arrays.asList(store("V1","R1")),Collections.emptyList(),10,false,false,false));assertEquals(6L,b.get(0).get("available"));}
    @Test void partialSingleWarehouseSkuStaysInOriginalWarehouseAfterReplenishment(){List<Map<String,Object>> stores=Arrays.asList(store("V1","R1"),store("V2","R2"));List<Map<String,Object>> first=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",4)),stores,Collections.emptyList(),10,false,true,false);assertEquals(6L,first.get(0).get("quantity"));List<Map<String,Object>> next=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",4)),Arrays.asList(batch(1,"V1","A",4),batch(2,"V2","A",4)),stores,first,10,false,true,false);assertEquals("R1",next.get(0).get("real_store_code"));}
}
