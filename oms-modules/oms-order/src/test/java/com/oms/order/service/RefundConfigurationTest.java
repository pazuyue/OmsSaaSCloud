package com.oms.order.service;

import org.junit.jupiter.api.*;
import com.ruoyi.common.core.domain.R;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Independent config saves, tenant isolation and read-only batched overview. */
class RefundConfigurationTest {
    RefundServiceTest f=new RefundServiceTest();RefundConfiguration settings;RefundFlowAutomation flow;
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){f.reset();f.db.update("DELETE FROM oms_refund_parcel_config WHERE company_code='VERIFY'");settings=new RefundConfiguration();settings.refunds=f.refunds;settings.catalog=mock(OrderCatalog.class);settings.platform=mock(RefundPlatformClient.class);settings.flow=flow=new RefundFlowAutomation();flow.refunds=f.refunds;flow.catalog=settings.catalog;settings.reader=mock(ConfiguredPushReader.class);settings.parcelPolicy=new RefundParcelPolicy();settings.parcelPolicy.refunds=f.refunds;
        when(settings.catalog.channels("VERIFY")).thenReturn(Arrays.asList(m("channel_id",1,"channel_name","测试店铺","channel_type","TM","enabled",1),m("channel_id",2,"channel_name","另一店铺","channel_type","TM","enabled",1)));
        when(settings.platform.capabilities(eq("VERIFY"),anyString())).thenReturn(R.ok(Arrays.asList(m("channel_id",1,"available",true,"can_query",true,"read_enabled",1,"write_enabled",0))));
    }
    @AfterEach void clear(){f.clear();}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("rows");}
    @Test void overviewIsReadOnlyBatchedAndCompanyScoped(){Map<String,Object> result=settings.list("VERIFY",mquery());assertEquals(2,number(result,"total"));assertEquals(1,number(rows(result).get(0),"channel_id"));verify(settings.platform,times(1)).capabilities(eq("VERIFY"),anyString());verify(settings.catalog,times(1)).channels("VERIFY");assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_api_cursor",Integer.class));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_flow_policy",Integer.class));assertThrows(IllegalArgumentException.class,()->settings.detail("OTHER",1));}
    Map<String,String> mquery(){return new HashMap<>();}
    @Test void apiSyncSaveUsesOwnVersionAndDoesNotEnableAutomation(){flow.saveApiSync("VERIFY",1,m("enabled",1,"work_version",0));assertEquals(1,number(RefundConfiguration.part(RefundConfiguration.part(settings.detail("VERIFY",1),"flow"),"api_sync"),"enabled"));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_flow_policy",Integer.class));assertThrows(IllegalArgumentException.class,()->flow.saveApiSync("VERIFY",1,m("enabled",0,"work_version",0)));long version=f.db.queryForObject("SELECT work_version FROM oms_refund_api_cursor WHERE company_code='VERIFY' AND channel_id=1",Long.class);flow.saveApiSync("VERIFY",1,m("enabled",0,"work_version",version));assertEquals(0,f.db.queryForObject("SELECT enabled FROM oms_refund_api_cursor WHERE company_code='VERIFY' AND channel_id=1",Integer.class));}
    @Test void savingAutomationCannotOverwriteIndependentApiSync(){flow.saveApiSync("VERIFY",1,m("enabled",1,"work_version",0));Map<String,Object> policy=RefundFlowAutomation.defaults();policy.put("enabled",0);policy.put("revision",0);flow.save("VERIFY",1,policy,"test");assertEquals(1,f.db.queryForObject("SELECT enabled FROM oms_refund_api_cursor WHERE company_code='VERIFY' AND channel_id=1",Integer.class));assertEquals(1,f.db.queryForObject("SELECT work_version FROM oms_refund_api_cursor WHERE company_code='VERIFY' AND channel_id=1",Integer.class));}
    @Test void configuredActionsReportMissingCapabilitiesAndFiltersRetainThem(){Map<String,Object> policy=RefundFlowAutomation.defaults();policy.put("auto_review",1);policy.put("enabled",1);policy.put("revision",0);flow.save("VERIFY",1,policy,"test");Map<String,String> q=mquery();q.put("status","INCOMPLETE");List<Map<String,Object>> rows=rows(settings.list("VERIFY",q));assertEquals(1,rows.size());assertFalse(((List<?>)rows.get(0).get("checks")).isEmpty());q.put("status","AUTOMATIC");assertEquals(1,number(settings.list("VERIFY",q),"total"));q.put("keyword","另一");assertEquals(0,number(settings.list("VERIFY",q),"total"));}
    @Test void capabilityFailureRemainsUnknownAndDoesNotPretendUnauthorized(){when(settings.platform.capabilities(eq("VERIFY"),anyString())).thenThrow(new IllegalStateException("unavailable"));Map<String,Object> result=settings.list("VERIFY",mquery());assertEquals(true,result.get("capability_unavailable"));assertEquals(false,RefundConfiguration.part(rows(result).get(0),"access").get("available"));}
    @Test void paymentReadinessDoesNotDependOnReturnWarehouseMasterSwitch(){Map<String,Object> row=m("shop_enabled",1,"flow",m("enabled",1,"auto_pay",1,"auto_review",1,"funds_owner","OMS","payment_limit","20"),"access",m("available",true,"can_query",true,"read_enabled",1,"write_enabled",1,"payment_enabled",1,"payment_authorized",true,"ignore_code",1),"returns",RefundParcelPolicy.defaults());assertTrue(RefundConfiguration.checks(row).isEmpty());RefundConfiguration.part(row,"access").put("payment_authorized",false);assertEquals(1,RefundConfiguration.checks(row).size());}
}
