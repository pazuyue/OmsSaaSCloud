package com.oms.order.service;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.order.service.OrderValues.*;

class OrderShipmentPackageServiceTest {
    OrderVirtualOutboundServiceTest f=new OrderVirtualOutboundServiceTest();OrderShipmentPackageService service;long line;
    @BeforeAll static void start(){OrderVirtualOutboundServiceTest.start();}
    @BeforeEach void seed(){f.seed();f.submit(1,4);f.service.process("VERIFY",f.shipment);line=f.fixture.db.queryForObject("SELECT id FROM oms_order_shipment_line",Long.class);service=new OrderShipmentPackageService();ReflectionTestUtils.setField(service,"repository",f.fixture.repository);ReflectionTestUtils.setField(service,"locks",f.locks);}
    Map<String,Object> row(Object...pairs){return f.row(pairs);}
    Map<String,Object> input(String request,String express,long quantity,long version){return row("request_id",request,"express_code",express,"control_version",version,"lines",Arrays.asList(row("id",line,"quantity",quantity)));}
    @Test void manualParcelsAssociateOnlyConfirmedGoodsAndRetryIsIdempotent(){Map<String,Object> p=input("PACKAGE_REQUEST_0001","TEST-VIRTUAL-001",2,3);assertEquals(true,service.register("VERIFY",f.shipment,p,"tester").get("success"));assertEquals(true,service.register("VERIFY",f.shipment,p,"tester").get("success"));assertEquals(4,number(f.document(),"shipped_quantity"));assertEquals(2,number(f.document(),"packaged_quantity"));assertEquals(2,number(f.document(),"unpacked_quantity"));assertEquals(1,number(f.document(),"package_count"));assertEquals(true,service.register("VERIFY",f.shipment,input("PACKAGE_REQUEST_0002","TEST-VIRTUAL-002",2,4),"tester").get("success"));assertEquals(0,number(f.document(),"unpacked_quantity"));assertEquals(2,((List<?>)f.document().get("packages")).size());assertEquals(1,((List<?>)f.document().get("outbound_records")).size());}
    @Test void overpackingWrongVersionChangedRequestAndForeignCompanyAreRejected(){Map<String,Object> p=input("PACKAGE_REQUEST_0010","TEST-VIRTUAL-010",2,3);service.register("VERIFY",f.shipment,p,"tester");assertThrows(IllegalArgumentException.class,()->service.register("VERIFY",f.shipment,input("PACKAGE_REQUEST_0010","TEST-VIRTUAL-010",3,3),"tester"));assertThrows(IllegalArgumentException.class,()->service.register("VERIFY",f.shipment,input("PACKAGE_REQUEST_0011","TEST-VIRTUAL-011",3,4),"tester"));assertThrows(IllegalArgumentException.class,()->service.register("VERIFY",f.shipment,input("PACKAGE_REQUEST_0012","TEST-VIRTUAL-012",2,3),"tester"));assertThrows(IllegalArgumentException.class,()->service.register("OTHER",f.shipment,p,"tester"));assertEquals(2,number(f.document(),"packaged_quantity"));assertEquals(4,number(f.document(),"shipped_quantity"));}
    @Test void unshippedAndWarehouseDocumentsCannotGetManualParcels(){f.fixture.db.update("UPDATE oms_order_shipment_line SET shipped_quantity=0");assertThrows(IllegalArgumentException.class,()->service.register("VERIFY",f.shipment,input("PACKAGE_REQUEST_0020","TEST-VIRTUAL-020",1,3),"tester"));f.fixture.db.update("DELETE FROM oms_order_shipment_virtual_outbound");assertThrows(IllegalArgumentException.class,()->service.register("VERIFY",f.shipment,input("PACKAGE_REQUEST_0021","TEST-VIRTUAL-021",1,3),"tester"));assertEquals(0,number(f.document(),"package_count"));}
}
