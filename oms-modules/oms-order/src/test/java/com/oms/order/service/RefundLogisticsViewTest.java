package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundLogisticsViewTest {
    Map<String,Object> valid(){return m("source_state","VALID","source_modified","2026-10-24 10:00:00");}
    @Test void confirmedParcelWinsOverEmptyPlanFieldsAndKeepsItsRealRegistrationTime(){
        Map<String,Object> box=m("id",31,"waybill","RETURN-1","carrier_code","SF","transport_state","WAITING","create_time","2026-10-24 11:00:00","warehouse_environment","TEST","members",Arrays.asList(m("refund_id",1),m("refund_id",2)));
        Map<String,Object> info=RefundLogisticsView.returnLogistics(m(),box,valid(),m("sid","RETURN-1","company_name","SF"),true);
        assertEquals("BOUND",info.get("state"));assertEquals("RETURN-1",info.get("waybill"));assertEquals("PLATFORM",info.get("source"));assertEquals(2,info.get("refund_count"));assertEquals("PARCEL_REGISTERED",info.get("time_kind"));assertEquals("TEST",info.get("warehouse_environment"));
        assertEquals("物流状态尚未更新，仓库已确认实收",RefundLogisticsView.transportDisplay(info,1));
        assertEquals("尚未取得退回物流进度",RefundLogisticsView.transportDisplay(info,0));
    }
    @Test void changedPlatformWaybillCannotReplaceTheConfirmedParcel(){
        Map<String,Object> info=RefundLogisticsView.returnLogistics(m(),m("id",31,"carrier_code","SF","waybill","OLD"),valid(),m("sid","NEW","company_name","SF"),true);
        assertEquals("OLD",info.get("waybill"));assertEquals(true,info.get("source_conflict"));assertEquals("CONFIRMED_PARCEL",info.get("source"));assertEquals("NEW",info.get("latest_claimed_waybill"));
    }
    @Test void carrierConflictIsVisibleWhileAliasesMatchTheCanonicalParcel(){
        Map<String,Object> box=m("id",31,"carrier_code","SF","waybill","SAME");Map<String,Object> info=RefundLogisticsView.returnLogistics(m(),box,valid(),m("sid","SAME","company_name","顺丰快递"),true);assertEquals(false,info.get("source_conflict"));
        info=RefundLogisticsView.returnLogistics(m(),box,valid(),m("sid","SAME","company_name","中通快递"),true);assertEquals(true,info.get("source_conflict"));assertEquals("SF",info.get("carrier"));assertEquals("中通快递",info.get("latest_claimed_carrier"));
    }
    @Test void parcelMetadataWithoutWarehouseNumberDoesNotBecomeAnInboundDocument(){Map<String,Object> row=RefundDispositionService.returnResult(m("result_json",m("parcel_id",31,"parcel_sn","RPK1")),null);assertTrue(RefundDispositionService.rows(row.get("documents")).isEmpty());}
    @Test void missingOrUnavailableReturnLogisticsNeverCopiesTheOutboundWaybill(){
        Map<String,Object> info=RefundLogisticsView.returnLogistics(m("return_waybill","","parcel_state","LINKED"),null,valid(),m(),false);
        assertEquals("UNAVAILABLE",info.get("state"));assertEquals("",info.get("waybill"));
        info=RefundLogisticsView.returnLogistics(m(),null,valid(),m(),true);assertEquals("MISSING",info.get("state"));assertEquals("",info.get("waybill"));
    }
    @Test void untrustedPlatformLogisticsCannotBecomeAnUnboundReturnWaybill(){
        Map<String,Object> info=RefundLogisticsView.returnLogistics(m(),null,m("source_state","CONFLICT"),m("sid","BAD","company_name","SF"),true);assertEquals("MISSING",info.get("state"));assertEquals("",info.get("waybill"));
    }
    @Test void suppliedManualWaybillHasNoInventedRegistrationTime(){
        Map<String,Object> info=RefundLogisticsView.returnLogistics(m("logistics_source","MANUAL","return_carrier","SF","return_waybill","MANUAL-1","update_time","2026-10-24 12:00:00"),null,valid(),m(),true);
        assertEquals("PROVIDED",info.get("state"));assertEquals("MANUAL",info.get("source"));assertNull(info.get("registered_at"));
    }
    @Test void successfulPlatformApplicationDoesNotEstablishPhysicalInterception(){
        Map<String,Object> summary=RefundLogisticsSummary.project(true,Collections.singletonList(m("action","INTERCEPT","state","SUCCESS","create_time","now")));
        assertEquals("SUCCESS",obj(summary.get("application")).get("state"));assertTrue(obj(summary.get("reported_outcome")).isEmpty());assertEquals(false,summary.get("courier_query_supported"));
    }
    @Test void feedbackRetainsItsDeclaredSourceAndSubmissionState(){
        Map<String,Object> op=m("action","INTERCEPT_FEEDBACK","state","UNKNOWN","actor","seller","request_json",m("logistic_status","DELIVERY_INTERCEPT_SUCCESS"));
        Map<String,Object> result=obj(RefundLogisticsSummary.project(true,Collections.singletonList(op)).get("reported_outcome"));
        assertEquals("SELLER_FEEDBACK",result.get("source"));assertEquals(false,result.get("confirmed"));op.put("state","SUCCESS");assertEquals(true,obj(RefundLogisticsSummary.project(true,Collections.singletonList(op)).get("reported_outcome")).get("confirmed"));
    }

    @Test void oneHundredPlansUseOneParcelLookupAndOneLocalCapabilityLookup(){
        RefundServiceTest.start();RefundServiceTest f=new RefundServiceTest();f.reset();
        try{
            long id=f.convert(f.ingest(f.source(1,"25.00","WAIT_SELLER_AGREE")));Map<String,Object> b=f.refunds.business("VERIFY",id,false);
            RefundLogisticsView view=new RefundLogisticsView();view.refunds=f.refunds;view.returns=mock(RefundReturnClient.class);view.channel=mock(RefundPlatformClient.class);
            when(view.returns.parcels(eq("VERIFY"),eq(id),anyString())).thenReturn(com.ruoyi.common.core.domain.R.ok(Collections.emptyList()));when(view.channel.capability(eq("VERIFY"),eq(1L),anyString())).thenReturn(com.ruoyi.common.core.domain.R.ok(m("execution_mode","SIMULATION")));
            List<Map<String,Object>> plans=new ArrayList<>(),display=new ArrayList<>();for(int i=1;i<=100;i++){plans.add(m("id",i,"return_mode","INTERCEPT","quantity",1));display.add(m("plan_id",i,"quantity",1,"accepted_quantity",0,"posted_quantity",0));}
            Map<String,Object> result=view.read("VERIFY",b,plans,Collections.emptyList(),display);
            assertEquals(100,RefundDispositionService.rows(result.get("returns")).size());assertEquals("SIMULATION",result.get("platform_mode"));
            verify(view.returns,times(1)).parcels(anyString(),anyLong(),anyString());verify(view.channel,times(1)).capability(anyString(),anyLong(),anyString());verifyNoMoreInteractions(view.returns,view.channel);
        }finally{f.clear();}
    }
}
