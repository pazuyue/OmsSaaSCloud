package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.oms.order.service.OrderValues.*;
class OrderShipmentScanTest {
    OrderShipmentDispatchServiceTest f=new OrderShipmentDispatchServiceTest();OrderShipmentDispatchWorker worker;OrderShipmentDispatchService dispatch;OrderShipmentReceiptService receipts;OrderVirtualOutboundService virtual;OrderSalesDocumentService sales;
    @BeforeAll static void start(){OrderShipmentDispatchServiceTest.setup();}
    @BeforeEach void seed(){f.seed();f.create(10);f.submit(1);worker=new OrderShipmentDispatchWorker();dispatch=mock(OrderShipmentDispatchService.class);receipts=mock(OrderShipmentReceiptService.class);virtual=mock(OrderVirtualOutboundService.class);sales=mock(OrderSalesDocumentService.class);DynamicRoutingDataSource routing=mock(DynamicRoutingDataSource.class);DataSource ds=f.fixture.db.getDataSource();when(routing.getDataSources()).thenReturn(Collections.singletonMap("tenant",ds));when(routing.getDataSource("tenant")).thenReturn(ds);ReflectionTestUtils.setField(worker,"dataSource",routing);ReflectionTestUtils.setField(worker,"dispatch",dispatch);ReflectionTestUtils.setField(worker,"receipts",receipts);ReflectionTestUtils.setField(worker,"virtualOutbound",virtual);ReflectionTestUtils.setField(worker,"salesDocuments",sales);}
    @AfterEach void clean(){f.clear();DynamicDataSourceContextHolder.clear();}
    @Test void dispatchIsIndependentAndCountsPersistedOutcome(){doAnswer(i->{f.fixture.db.update("UPDATE oms_order_shipment_dispatch SET state='ACCEPTED' WHERE shipment_id=?",f.shipment);return null;}).when(dispatch).process("VERIFY",f.shipment);Map<String,Object> report=worker.scan("dispatch");assertEquals(1,number(report,"scanned"));assertEquals(1,number(report,"succeeded"));assertEquals(0,number(worker.scan("dispatch"),"scanned"));verifyNoInteractions(receipts,virtual,sales);assertNull(DynamicDataSourceContextHolder.peek());}
    @Test void failureDoesNotBecomeSuccessOrLeakTenantContext(){doThrow(new IllegalStateException("private detail")).when(dispatch).process("VERIFY",f.shipment);Map<String,Object> report=worker.scan("dispatch");assertEquals(1,number(report,"failed"));assertEquals(0,number(report,"succeeded"));assertFalse(report.toString().contains("private detail"));assertNull(DynamicDataSourceContextHolder.peek());assertThrows(IllegalArgumentException.class,()->worker.scan("arbitrary"));}
    @Test void onlyExactAuthenticatedInternalPostsCanDiscoverAllTenants() throws Exception {
        com.oms.common.filter.DynamicDatasourceInterceptorFilter filter=new com.oms.common.filter.DynamicDatasourceInterceptorFilter();
        for(String path:Arrays.asList("/orders/internal/schedule/dispatch","/orders/internal/schedule/receipts","/orders/internal/schedule/virtual","/orders/internal/schedule/sales","/orders/internal/schedule/platform","/orders/internal/schedule/issues","/shipmentWms/internal/schedule/inbound")){
            org.springframework.mock.web.MockHttpServletRequest internal=new org.springframework.mock.web.MockHttpServletRequest("POST",path);internal.addHeader(com.ruoyi.common.core.constant.SecurityConstants.FROM_SOURCE,com.ruoyi.common.core.constant.SecurityConstants.INNER);org.springframework.mock.web.MockHttpServletResponse response=new org.springframework.mock.web.MockHttpServletResponse();
            DynamicDataSourceContextHolder.push("previous");filter.doFilter(internal,response,(req,res)->assertNull(DynamicDataSourceContextHolder.peek()));assertNull(DynamicDataSourceContextHolder.peek());
            org.springframework.mock.web.MockHttpServletResponse rejected=new org.springframework.mock.web.MockHttpServletResponse();filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("POST",path),rejected,(req,res)->fail("Public request must not scan all tenants"));assertEquals(400,rejected.getStatus());
        }
        org.springframework.mock.web.MockHttpServletRequest arbitrary=new org.springframework.mock.web.MockHttpServletRequest("POST","/orders/internal/schedule/arbitrary");arbitrary.addHeader(com.ruoyi.common.core.constant.SecurityConstants.FROM_SOURCE,com.ruoyi.common.core.constant.SecurityConstants.INNER);org.springframework.mock.web.MockHttpServletResponse rejected=new org.springframework.mock.web.MockHttpServletResponse();filter.doFilter(arbitrary,rejected,(req,res)->fail("Unknown scan must not bypass company routing"));assertEquals(400,rejected.getStatus());
    }
    @Test void scansHaveNoEmbeddedTimers(){assertNull(com.oms.order.OrderApplication.class.getAnnotation(org.springframework.scheduling.annotation.EnableScheduling.class));for(Class<?> type:Arrays.asList(OrderShipmentDispatchWorker.class,OrderPlatformShippingWorker.class,OrderIssueWorker.class))for(java.lang.reflect.Method method:type.getDeclaredMethods())assertNull(method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class));}
}
