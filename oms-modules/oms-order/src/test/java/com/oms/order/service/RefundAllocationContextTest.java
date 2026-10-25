package com.oms.order.service;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RefundAllocationContextTest {
    RefundGoodsServiceTest f;RefundAllocationContext service;long plan;
    @BeforeAll static void start(){RefundGoodsServiceTest.start();}
    @BeforeEach void setup(){f=new RefundGoodsServiceTest();f.setup();plan=f.create(2);service=new RefundAllocationContext();service.refunds=f.f.refunds;service.policy=mock(RefundParcelPolicy.class);service.catalog=mock(OrderCatalog.class);when(service.catalog.channels("VERIFY")).thenReturn(Collections.singletonList(m("channel_id",1,"enabled",1)));when(service.policy.get("VERIFY",1)).thenReturn(m("revision",7,"allocation_mode","RULE"));}
    @AfterEach void cleanup(){f.cleanup();}
    Map<String,Object> priority(){return list(service.context("VERIFY",1,Collections.singletonList(plan)).get("priorities")).get(0);}
    @Test void platformApplicationTimeIsUsedAndMissingTimeFallsBackToOmsCreation(){Map<String,Object> first=priority();assertEquals("OMS_CREATED_FALLBACK",first.get("priority_basis"));assertFalse(text(first,"refund_created").isEmpty());assertEquals(true,first.get("eligible"));Map<String,Object> b=f.f.refunds.business("VERIFY",f.refund,false),p=f.f.refunds.platform("VERIFY",number(b,"platform_refund_id"),false),raw=parse(text(p,"payload"));raw.put("created","2026-09-01 08:00:00");f.f.db.update("UPDATE oms_platform_refund SET payload=? WHERE id=?",encode(m("refund_get_response",m("refund",raw))),p.get("id"));assertEquals("PLATFORM_CREATED",priority().get("priority_basis"));assertEquals("2026-09-01 08:00:00",priority().get("refund_created"));}
    @Test void crossCompanyShopAndSourceCannotBeQueried(){assertThrows(IllegalArgumentException.class,()->service.context("OTHER",1,Collections.singletonList(plan)));assertThrows(IllegalArgumentException.class,()->service.context("VERIFY",2,Collections.singletonList(plan)));assertThrows(IllegalArgumentException.class,()->service.context("VERIFY",1,Arrays.asList(plan,plan)));assertThrows(IllegalArgumentException.class,()->service.context("VERIFY",1,Collections.singletonList(plan+999)));}
    @Test void stoppedPlanOrChangedApprovalIsIneligible(){f.f.db.update("UPDATE oms_refund_return_plan SET state='STOPPED' WHERE id=?",plan);assertEquals(false,priority().get("eligible"));f.f.db.update("UPDATE oms_refund_return_plan SET state='PLANNED' WHERE id=?",plan);f.f.db.update("UPDATE oms_refund SET approved_version=0 WHERE id=?",f.refund);assertEquals(false,priority().get("eligible"));}
    @Test void updatedCarrierMetadataDoesNotInvalidateAnAlreadyBoundOriginalWarehouseReceipt(){f.f.db.update("UPDATE oms_refund_return_plan SET parcel_issue=? WHERE id=?",encode(m("code","LOGISTICS_CHANGED")),plan);assertEquals(true,priority().get("eligible"));f.f.db.update("UPDATE oms_refund_return_plan SET parcel_issue=? WHERE id=?",encode(m("code","APPEND_AFTER_RECEIPT")),plan);assertEquals(false,priority().get("eligible"));}
}
