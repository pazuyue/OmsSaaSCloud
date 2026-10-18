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

    @Test void regionalPriorityCanSplitWhileWholeOrderStrategyUsesSecondWarehouse(){List<Map<String,Object>> sources=Arrays.asList(source(1,"A",10)),stock=Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",10)),stores=Arrays.asList(store("V1","R1"),store("V2","R1"));List<Map<String,Object>> regional=WarehouseAllocationPlanner.plan(sources,stock,stores,Collections.emptyList(),10,true,false,true,row("strategy","REGION_FIRST"));assertEquals(2,regional.size());assertEquals(6L,regional.get(0).get("quantity"));assertEquals(1,WarehouseAllocationPlanner.plan(sources,stock,stores,Collections.emptyList(),10,true,false,true,row("strategy","FEWEST_SHIPMENTS")).size());}
    @Test void warehouseCapAppliesAcrossPriorAllocationAndNoPlanMutatesStock(){List<Map<String,Object>> stock=Arrays.asList(batch(1,"V1","A",6),batch(2,"V2","A",4));assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),stock,Arrays.asList(store("V1","R1"),store("V2","R2")),Collections.emptyList(),10,true,false,true,row("max_virtual_warehouses",1)));assertEquals(6L,stock.get(0).get("available"));}
    @Test void shipmentCapCountsSupplementalDocumentsButDoesNotDoubleCountUnmirroredReceipts(){List<Map<String,Object>> prior=Arrays.asList(row("id",9,"store_code","V1","sku_sn","A","quantity",6));Map<String,Object> policy=row("max_shipments",1,"existing_shipment_count",0,"mapped_allocation_ids",Collections.emptyList());assertEquals(4L,WarehouseAllocationPlanner.quantity(WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",4)),Arrays.asList(batch(2,"V1","A",4)),Arrays.asList(store("V1","R1")),prior,10,true,true,true,policy)));policy.put("existing_shipment_count",1);policy.put("mapped_allocation_ids",Arrays.asList(9L));assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",4)),Arrays.asList(batch(2,"V1","A",4)),Arrays.asList(store("V1","R1")),prior,10,true,true,true,policy));}
    @Test void firstPartialThresholdDoesNotApplyToRemainingSupplement(){Map<String,Object> policy=row("min_first_allocation_percent",70);List<Map<String,Object>> stores=Arrays.asList(store("V1","R1"));assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",10)),Arrays.asList(batch(1,"V1","A",6)),stores,Collections.emptyList(),10,false,true,false,policy));assertEquals(4L,WarehouseAllocationPlanner.quantity(WarehouseAllocationPlanner.plan(Arrays.asList(source(1,"A",4)),Arrays.asList(batch(1,"V1","A",4)),stores,Arrays.asList(row("store_code","V1","sku_sn","A","quantity",6)),10,false,true,false,policy)));}
    @Test void coverageCandidateReducesGreedyThreeWarehousesToTwoWithinCap(){List<Map<String,Object>> sources=Arrays.asList(source(1,"A",1),source(2,"B",1),source(3,"C",1)),stores=Arrays.asList(store("V1","R1"),store("V2","R2"),store("V3","R3")),stock=Arrays.asList(batch(1,"V1","A",1),batch(2,"V2","A",1),batch(3,"V2","B",1),batch(4,"V3","C",1));List<Map<String,Object>> plan=WarehouseAllocationPlanner.plan(sources,stock,stores,Collections.emptyList(),3,true,false,true,row("max_virtual_warehouses",2));assertEquals(3L,WarehouseAllocationPlanner.quantity(plan));assertTrue(plan.stream().noneMatch(r->"V1".equals(r.get("store_code"))));}
    @Test void indexedPlannerHandlesTenThousandBatchesWithoutSubsetEnumeration(){List<Map<String,Object>> sources=new ArrayList<>(),stores=new ArrayList<>(),batches=new ArrayList<>();for(int i=0;i<100;i++){sources.add(source(i+1,"S"+i,100));stores.add(store("V"+i,"R"+i));for(int j=0;j<100;j++)batches.add(batch(i*100+j+1,"V"+i,"S"+j,1));}long started=System.nanoTime();List<Map<String,Object>> result=WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),10000,true,false,true);assertEquals(10000L,WarehouseAllocationPlanner.quantity(result));assertTrue((System.nanoTime()-started)/1000000<5000,"bounded indexed planning regression");}
    @Test void largePartialCandidatesOnlyCopyStockOfTheirCurrentSku(){List<Map<String,Object>> sources=new ArrayList<>(),stores=new ArrayList<>(),batches=new ArrayList<>();for(int i=0;i<100;i++){sources.add(source(i+1,"S"+i,200));stores.add(store("V"+i,"R"+i));for(int j=0;j<100;j++)batches.add(batch(i*100+j+1,"V"+i,"S"+j,1));}long started=System.nanoTime();List<Map<String,Object>> result=WarehouseAllocationPlanner.plan(sources,batches,stores,Collections.emptyList(),20000,true,true,true);long elapsed=(System.nanoTime()-started)/1000000;assertEquals(10000L,WarehouseAllocationPlanner.quantity(result));assertTrue(elapsed<5000,"partial candidates must not copy all SKUs per trial");System.out.println("ALLOCATION_PARTIAL_10000_BATCHES_MS="+elapsed);}
    @Test void separateFulfillmentReferencesStillObeyWholeOrderWarehouseCap(){
        Map<String,Object> policy=row("max_virtual_warehouses",1,"global_fulfillment",Arrays.asList(row("sku_sn","A","store_code","V1")));
        assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(Arrays.asList(source(2,"B",2)),Arrays.asList(batch(2,"V2","B",2)),Arrays.asList(store("V1","R1"),store("V2","R2")),Collections.emptyList(),2,true,false,true,policy));
    }
    @Test void sameSkuReturningToAUsesExistingWarehouseEvenAcrossReferences(){
        Map<String,Object> policy=row("global_fulfillment",Arrays.asList(row("sku_sn","A","store_code","V1")));
        List<Map<String,Object>> stores=Arrays.asList(store("V1","R1"),store("V2","R2")),sources=Arrays.asList(source(2,"A",2));
        assertThrows(IllegalArgumentException.class,()->WarehouseAllocationPlanner.plan(sources,Arrays.asList(batch(2,"V2","A",2)),stores,Collections.emptyList(),2,true,false,false,policy));
        List<Map<String,Object>> selected=WarehouseAllocationPlanner.plan(sources,Arrays.asList(batch(1,"V1","A",2),batch(2,"V2","A",2)),stores,Collections.emptyList(),2,true,false,false,policy);
        assertEquals("V1",selected.get(0).get("store_code"));
    }
}
