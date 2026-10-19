package com.oms.inventory.service.impl;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BundleWarehousePlannerTest {
 Map<String,Object> m(Object... v){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)r.put(v[i].toString(),v[i+1]);return r;}
 List<Map<String,Object>> stores(){return Arrays.asList(m("store_code","V1","owner_code","O","real_store_code","W"),m("store_code","V2","owner_code","O","real_store_code","W"));}
 List<Map<String,Object>> sources(){return Arrays.asList(m("id",1,"demand_id",11,"platform_oid","99","sku_sn","A","quantity",4,"source_type","PUBLIC_EXTRA","bundle_id",1,"component_quantity",2),m("id",2,"demand_id",12,"platform_oid","99","sku_sn","B","quantity",2,"source_type","PUBLIC_EXTRA","bundle_id",1,"component_quantity",1));}
 Map<String,Object> policy(){return m("lines",sources(),"strategy","REGION_FIRST");}
 List<Map<String,Object>> batches(long a1,long b1,long a2,long b2){return Arrays.asList(m("id",1,"store_code","V1","sku_sn","A","available",a1),m("id",2,"store_code","V1","sku_sn","B","available",b1),m("id",3,"store_code","V2","sku_sn","A","available",a2),m("id",4,"store_code","V2","sku_sn","B","available",b2));}
 List<Map<String,Object>> run(List<Map<String,Object>> b,boolean partial,Map<String,Object> p){return BundleWarehousePlanner.plan(sources(),b,stores(),Collections.emptyList(),6,true,partial,false,p);}
 long qty(List<Map<String,Object>> p,String sku,String wh){return p.stream().filter(r->sku.equals(r.get("sku_sn"))&&wh.equals(r.get("store_code"))).mapToLong(r->((Number)r.get("quantity")).longValue()).sum();}
 @Test void twoSetsUseTwoWarehousesButEachSetRemainsComplete(){List<Map<String,Object>> p=run(batches(2,1,2,1),false,policy());assertEquals(2,qty(p,"A","V1"));assertEquals(1,qty(p,"B","V1"));assertEquals(2,qty(p,"A","V2"));assertEquals(1,qty(p,"B","V2"));}
 @Test void splitComponentsCannotMasqueradeAsCompleteSameWarehouseKits(){assertThrows(IllegalArgumentException.class,()->run(batches(4,0,0,2),true,policy()));}
 @Test void partialAllocationOnlyTakesWholeSets(){List<Map<String,Object>> p=run(batches(4,1,0,0),true,policy());assertEquals(3,WarehouseAllocationPlanner.quantity(p));assertEquals(2,qty(p,"A","V1"));assertEquals(1,qty(p,"B","V1"));}
 @Test void explicitCrossWarehouseSwitchAllowsOneKitsComponentsAcrossWarehouses(){Map<String,Object> p=policy();p.put("allow_bundle_split",1);assertEquals(6,WarehouseAllocationPlanner.quantity(run(batches(4,0,0,2),false,p)));}
 @Test void explicitPartialComponentsSwitchCanSendOnlyAvailableComponents(){Map<String,Object> p=policy();p.put("allow_bundle_partial_components",1);List<Map<String,Object>> plan=run(batches(4,0,0,0),true,p);assertEquals(4,WarehouseAllocationPlanner.quantity(plan));assertEquals(0,qty(plan,"B","V1"));}
 @Test void noPartialMeansShortCompleteSetPlanCannotOccupyAnything(){assertThrows(IllegalArgumentException.class,()->run(batches(4,1,0,0),false,policy()));}
 @Test void shipmentBudgetAndMinimumCoverageStillApplyToKits(){Map<String,Object> p=policy();p.put("max_shipments",1);p.put("existing_shipment_count",0);p.put("mapped_allocation_ids",Collections.emptyList());assertThrows(IllegalArgumentException.class,()->run(batches(2,1,2,1),false,p));p.remove("max_shipments");p.put("min_first_allocation_percent",75);assertThrows(IllegalArgumentException.class,()->run(batches(2,1,0,0),true,p));}
 @Test void plannerDoesNotMutateCallerStock(){List<Map<String,Object>> b=batches(2,1,2,1);String before=b.toString();run(b,false,policy());assertEquals(before,b.toString());}
}
