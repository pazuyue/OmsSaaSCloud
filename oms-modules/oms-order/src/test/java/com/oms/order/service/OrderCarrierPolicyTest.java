package com.oms.order.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.order.service.OrderValues.*;

class OrderCarrierPolicyTest {
    Map<String,Object> row(Object...pairs){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)r.put(pairs[i].toString(),pairs[i+1]);return r;}
    Map<String,Object> route(String owner,String store,List<String> provinces,String...codes){return row("owner_code",owner,"real_store_code",store,"provinces",provinces,"carrier_order",Arrays.asList(codes));}
    @Test void provinceNormalizationAcceptsAdministrativeNamesAndRejectsUnknowns(){assertEquals("北京",OrderCarrierPolicy.province(" 北京市 "));assertEquals("新疆",OrderCarrierPolicy.province("新疆维吾尔自治区"));assertEquals("广西",OrderCarrierPolicy.province("广西壮族自治区"));assertEquals("香港",OrderCarrierPolicy.province("香港特别行政区"));assertEquals("",OrderCarrierPolicy.province("北京某区"));assertEquals("",OrderCarrierPolicy.province(null));}
    @Test void physicalWarehouseAndProvincePriorityFallsBackOnlyToAllowedShopCarriers(){
        Map<String,Object> p=row("enabled",1,"carrier_order",Arrays.asList("A","B","C"),"routes",Arrays.asList(route("","",Arrays.asList("北京"),"B"),route("O1","R1",Collections.emptyList(),"C"),route("O1","R1",Arrays.asList("北京"),"B","C")));
        Set<String> carriers=new HashSet<>(Arrays.asList("A","B","C"));Set<String> stores=new HashSet<>(Arrays.asList(encode(Arrays.asList("O1","R1"))));p=OrderCarrierPolicy.validate(p,carriers,stores);
        assertEquals(Arrays.asList("B","C","A"),OrderCarrierPolicy.ordered(p,row("owner_code","O1","real_store_code","R1","receiver_province","北京")));
        assertEquals(Arrays.asList("C","A","B"),OrderCarrierPolicy.ordered(p,row("owner_code","O1","real_store_code","R1","receiver_province","上海")));
        assertEquals(Arrays.asList("B","A","C"),OrderCarrierPolicy.ordered(p,row("owner_code","O2","real_store_code","R1","receiver_province","北京")));
    }
    @Test void ambiguousRoutesAndCarriersOutsideAllowListAreRejected(){Set<String> cs=new HashSet<>(Arrays.asList("A","B"));Set<String> ws=Collections.emptySet();Map<String,Object> p=row("enabled",1,"carrier_order",Arrays.asList("A"),"routes",Arrays.asList(route("","",Arrays.asList("北京"),"B")));assertThrows(IllegalArgumentException.class,()->OrderCarrierPolicy.validate(p,cs,ws));p.put("routes",Arrays.asList(route("","",Arrays.asList("北京","上海"),"A"),route("","",Arrays.asList("上海"),"A")));assertThrows(IllegalArgumentException.class,()->OrderCarrierPolicy.validate(p,cs,ws));}
    @Test void coverageNeverCrossesOwnersOrExcludedProvince(){Map<String,Object> shipment=row("owner_code","O1","real_store_code","R1","receiver_province","北京");Map<String,Object> carrier=row("enabled",1,"services",Arrays.asList(row("owner_code","O1","real_store_code","R1","provinces",Collections.emptyList(),"excluded_provinces",Arrays.asList("北京"))));assertFalse(OrderCarrierPolicy.unavailable(carrier,shipment).isEmpty());shipment.put("receiver_province","上海");assertEquals("",OrderCarrierPolicy.unavailable(carrier,shipment));shipment.put("owner_code","O2");assertFalse(OrderCarrierPolicy.unavailable(carrier,shipment).isEmpty());}
}
