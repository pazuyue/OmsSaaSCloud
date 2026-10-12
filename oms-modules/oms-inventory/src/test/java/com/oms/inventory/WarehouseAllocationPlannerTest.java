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
    @Test void sameRealWarehouseMayUseSeveralVirtualWarehousesWithoutMultiShipping(){List<Map<String,Object>> p=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",4)),Arrays.asList(store("V1","R1"),store("V2","R1")),Collections.emptyList(),10,false,false,false);assertEquals(2,p.size());assertTrue(p.stream().allMatch(r->"R1".equals(r.get("real_store_code"))));}
    @Test void skuLinesAreGroupedBeforeChoosingWarehouse(){List<Map<String,Object>> p=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",6),source(2,"A",6)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",12)),Arrays.asList(store("V1","R1"),store("V2","R2")),Collections.emptyList(),12,true,false,false);assertTrue(p.stream().allMatch(r->"R2".equals(r.get("real_store_code"))));}
    @Test void failedWholeOrderPlanDoesNotConsumeCallerCapacity(){List<Map<String,Object>> b=Arrays.asList(batch(1,"V1","A",6));assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),b,Arrays.asList(store("V1","R1")),Collections.emptyList(),10,false,false,false));assertEquals(6L,b.get(0).get("available"));}
    @Test void partialSingleWarehouseSkuStaysInOriginalWarehouseAfterReplenishment(){List<Map<String,Object>> stores=Arrays.asList(store("V1","R1"),store("V2","R2"));List<Map<String,Object>> first=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",4)),stores,Collections.emptyList(),10,false,true,false);assertEquals(6L,first.get(0).get("quantity"));List<Map<String,Object>> next=WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",4)),Arrays.asList(batch(1,"V1","A",4),batch(2,"V2","A",4)),stores,first,10,false,true,false);assertEquals("R1",next.get(0).get("real_store_code"));}
}
