package com.oms.order.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
class RefundFulfillmentTest {
 @Test void oneSuborderAcrossWarehousesKeepsShippedAndUnshippedScopesSeparate(){
  Map<String,Object> line=m("order_line_id",1,"quantity",5,"cancelled_quantity",0);
  List<Map<String,Object>> sources=Arrays.asList(m("order_line_id",1,"quantity",2,"shipped_quantity",2,"cancelled_quantity",0,"status","SHIPPED","dispatch_state","ACCEPTED"),m("order_line_id",1,"quantity",2,"shipped_quantity",0,"cancelled_quantity",0,"status","WAREHOUSE_PROCESSING","dispatch_state","ACCEPTED"));
  Map<String,Object> result=RefundFulfillment.summarize(Collections.singletonList(line),sources,false);
  assertEquals("PART_SHIPPED",result.get("stage"));assertEquals(2,number(result,"shipped_quantity"));assertEquals(3,number(result,"unshipped_quantity"));assertEquals("CANCEL_WAREHOUSE",sources.get(1).get("suggested_action"));
 }
 @Test void zeroRecordedOutboundIsNotSafeWhenWarehouseResultIsUnknown(){Map<String,Object> result=RefundFulfillment.summarize(Collections.singletonList(m("order_line_id",1,"quantity",3)),Collections.singletonList(m("order_line_id",1,"quantity",3,"shipped_quantity",0,"dispatch_state","UNKNOWN")),false);assertEquals("UNKNOWN",result.get("stage"));assertEquals(true,result.get("uncertain"));}
 @Test void confirmedWarehouseAcceptanceIsStillUnshipped(){Map<String,Object> result=RefundFulfillment.summarize(Collections.singletonList(m("order_line_id",1,"quantity",3)),Collections.singletonList(m("order_line_id",1,"quantity",3,"dispatch_state","ACCEPTED","other_unshipped_quantity",2)),false);assertEquals("UNSHIPPED",result.get("stage"));assertEquals(3,number(result,"warehouse_pending_quantity"));}
 @Test void cancelledDemandDoesNotRemainOutstanding(){assertEquals("SHIPPED",RefundFulfillment.stage(2,2,false));assertEquals("CANCELED",RefundFulfillment.stage(0,0,false));assertEquals("UNKNOWN",RefundFulfillment.stage(2,3,false));assertEquals("UNKNOWN",RefundFulfillment.stage(0,0,true));}
 @Test void matchingKitComponentsMapBackToOriginalSalesUnits(){List<Map<String,Object>> lines=Arrays.asList(m("id",1,"quantity",4,"component_quantity",2,"sales_quantity",2),m("id",2,"quantity",6,"component_quantity",3,"sales_quantity",2));Map<Long,Long> selected=new HashMap<>();selected.put(1L,2L);selected.put(2L,3L);assertEquals(1,RefundMixedScope.bundleUnits(lines,selected));selected.put(2L,2L);assertEquals(-1,RefundMixedScope.bundleUnits(lines,selected));}
 @Test void randomBagComponentSubsetDoesNotGuessSalesBagCount(){List<Map<String,Object>> lines=Arrays.asList(m("id",1,"quantity",2,"component_quantity",0,"sales_quantity",2),m("id",2,"quantity",2,"component_quantity",0,"sales_quantity",2));Map<Long,Long> selected=new HashMap<>();selected.put(1L,1L);selected.put(2L,1L);assertEquals(-1,RefundMixedScope.bundleUnits(lines,selected));selected.put(1L,2L);selected.put(2L,2L);assertEquals(2,RefundMixedScope.bundleUnits(lines,selected));}
}
