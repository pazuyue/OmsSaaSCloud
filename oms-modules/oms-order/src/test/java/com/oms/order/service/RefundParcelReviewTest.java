package com.oms.order.service;
import org.junit.jupiter.api.*;
import java.util.*;
import com.ruoyi.common.core.domain.R;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RefundParcelReviewTest {
    RefundGoodsServiceTest f;RefundParcelReview review;RefundParcelWorkspace parcels;long plan;
    @BeforeAll static void start(){RefundGoodsServiceTest.start();}
    @BeforeEach void setup(){f=new RefundGoodsServiceTest();f.setup();plan=f.create(1);Map<String,Object> issue=RefundParcelIssues.afterReceipt(m("id",70,"parcel_sn","RPK_TARGET","carrier_code","SF","waybill","OLD_WAYBILL","store_code","V"));f.f.db.update("UPDATE oms_refund_return_plan SET parcel_state='NEED_CHECK',parcel_error='原包裹已有实收',parcel_issue=? WHERE id=?",encode(issue),plan);parcels=new RefundParcelWorkspace();parcels.refunds=f.f.refunds;parcels.returns=f.goods.returns;parcels.goods=f.goods;parcels.policy=mock(RefundParcelPolicy.class);when(parcels.policy.get(anyString(),anyLong())).thenReturn(RefundParcelPolicy.defaults());when(parcels.returns.attachParcel(anyString(),anyLong(),anyMap(),anyString())).thenReturn(R.ok(m("id",70,"parcel_sn","RPK_TARGET")));when(parcels.returns.parcels(anyString(),anyLong(),anyString())).thenReturn(R.ok(Collections.emptyList()));review=new RefundParcelReview();review.refunds=f.f.refunds;review.parcels=parcels;review.goods=f.goods;review.returns=f.goods.returns;}
    @AfterEach void cleanup(){f.cleanup();}
    Map<String,Object> input(String decision){Map<String,Object> v=f.command(plan);v.putAll(m("decision",decision,"warehouse_verified",true,"buyer_verified",true,"parcel_version",3));return v;}
    @Test void listAndDetailExposeActionableIssueWithoutChangingFundsOrGoodsFacts(){Map<String,Object> b=f.f.workspace.detail("VERIFY",f.refund);List<?> issues=(List<?>)b.get("parcel_issues");assertEquals(1,issues.size());Map<?,?> issue=(Map<?,?>)issues.get(0);assertEquals("APPEND_AFTER_RECEIPT",issue.get("code"));assertEquals("RPK_TARGET",issue.get("target_parcel_sn"));assertEquals(false,issue.get("has_document"));assertEquals(0,number(f.plan(plan),"posted_quantity"));}
    @Test void normalDispatchCannotBypassTheUnresolvedParcelIssue(){assertThrows(IllegalArgumentException.class,()->f.goods.action("VERIFY",f.refund,plan,0,"dispatch",f.command(plan),"test"));verify(f.goods.returns,never()).prepare(anyString(),anyLong(),anyMap(),anyString());}
    @Test void ordinaryAssociationCannotSpoofLateReviewFlag(){Map<String,Object> v=input("SAME_PARCEL");v.putAll(m("late_review",true,"carrier_code","SF","waybill","OLD_WAYBILL","join_parcel_id",70));parcels.attach("VERIFY",f.refund,plan,v,"test");verify(parcels.returns).attachParcel(eq("VERIFY"),eq(plan),argThat(body->Boolean.FALSE.equals(body.get("late_review"))),anyString());}
    @Test void receivedParcelCannotBeExtendedByCreatingAnIndependentThk(){assertThrows(IllegalArgumentException.class,()->review.decide("VERIFY",f.refund,plan,input("SAME_PARCEL"),"test"));assertEquals("NEED_CHECK",f.plan(plan).get("parcel_state"));assertNull(f.plan(plan).get("result_json"));verify(parcels.returns,never()).prepare(anyString(),anyLong(),anyMap(),anyString());}

    @Test void waitingBuyerAndWarehouseCheckHaveDistinctPersistentNextSteps(){review.decide("VERIFY",f.refund,plan,input("WAREHOUSE_CHECK"),"test");assertEquals("WAREHOUSE_CHECK",f.plan(plan).get("parcel_resolution"));assertNotNull(f.plan(plan).get("parcel_issue"));review.decide("VERIFY",f.refund,plan,input("WAIT_BUYER"),"test");assertEquals("WAIT_BUYER",f.plan(plan).get("parcel_resolution"));assertEquals("",text(f.plan(plan),"return_waybill"));assertNull(f.plan(plan).get("parcel_issue"));}
    @Test void correctedLogisticsKeepsPlatformFactAndStoresVerifiedManualSource(){Map<String,Object> v=input("CORRECT_LOGISTICS");v.putAll(m("carrier_code","SF","waybill","NEW_WAYBILL"));review.decide("VERIFY",f.refund,plan,v,"test");assertEquals("MANUAL",f.plan(plan).get("logistics_source"));assertEquals("NEW_WAYBILL",text(f.plan(plan),"return_waybill"));assertEquals("WAIT_LOGISTICS",f.plan(plan).get("parcel_state"));}
    @Test void reviewRejectsChangedScopeStaleVersionAndOtherCompany(){Map<String,Object> v=input("WAREHOUSE_CHECK");v.put("plan_version",0);assertThrows(IllegalArgumentException.class,()->review.decide("VERIFY",f.refund,plan,v,"test"));assertThrows(IllegalArgumentException.class,()->review.decide("OTHER",f.refund,plan,input("WAREHOUSE_CHECK"),"test"));f.f.db.update("UPDATE oms_refund_return_plan SET waived_quantity=1 WHERE id=?",plan);assertThrows(IllegalArgumentException.class,()->review.decide("VERIFY",f.refund,plan,input("WAREHOUSE_CHECK"),"test"));}
}
