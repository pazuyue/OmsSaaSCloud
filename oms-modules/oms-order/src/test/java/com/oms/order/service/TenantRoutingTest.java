package com.oms.order.service;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.common.tenant.TenantRouting;
import com.oms.common.filter.DynamicDatasourceInterceptorFilter;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantRoutingTest {
    DynamicRoutingDataSource routing;DataSource first,second;TenantRouting tenants;
    @BeforeEach void setup(){
        TenantRouting.clearContext();routing=mock(DynamicRoutingDataSource.class);first=mock(DataSource.class);second=mock(DataSource.class);
        Map<String,DataSource> pools=new HashMap<>();pools.put("master",first);pools.put("second",second);
        when(routing.getDataSources()).thenReturn(pools);when(routing.getDataSource("master")).thenReturn(first);when(routing.getDataSource("second")).thenReturn(second);
        tenants=new TenantRouting();ReflectionTestUtils.setField(tenants,"dataSource",routing);Map<String,String> routes=new HashMap<>();routes.put("QM","master");routes.put("BETA","second");routes.put("SHARED","master");tenants.setRoutes(routes);tenants.validate();
    }
    @AfterEach void clear(){TransactionSynchronizationManager.setActualTransactionActive(false);TenantRouting.clearContext();}
    @Test void unknownCompanyNeverUsesPrimary(){assertThrows(IllegalArgumentException.class,()->tenants.run("UNKNOWN",()->fail("must not execute")));verify(routing,never()).getDataSource("UNKNOWN");}
    @Test void backgroundWorkCannotAcceptAnUnvalidatedStringRoute() throws Exception {
        Class<?> executor=com.oms.common.task.ScopedWorkExecutor.class;
        assertThrows(NoSuchMethodException.class,()->executor.getMethod("submit",String.class,Runnable.class));
        assertNotNull(executor.getMethod("submit",TenantRouting.Route.class,Runnable.class));
        assertThrows(NoSuchMethodException.class,()->OrderImmediateWork.class.getMethod("salesChanged",String.class,String.class,long.class));
        assertNotNull(OrderImmediateWork.class.getMethod("salesChanged",String.class,long.class));
    }
    @Test void configurationRejectsMissingPoolAndDuplicateCompany(){tenants.setRoutes(Collections.singletonMap("QM","absent"));assertThrows(IllegalArgumentException.class,tenants::validate);verify(routing,never()).getDataSource("absent");Map<String,String> duplicate=new HashMap<>();duplicate.put("QM","master");duplicate.put("qm","second");assertThrows(IllegalArgumentException.class,()->tenants.setRoutes(duplicate));}
    @Test void emptyConfigurationFailsStartup(){tenants.setRoutes(Collections.emptyMap());assertThrows(IllegalArgumentException.class,tenants::validate);}
    @Test void unifiedPropertyNamesBindToTheCompanyRegistry(){tenants.setRoutes(Collections.emptyMap());Map<String,Object> values=new HashMap<>();values.put("oms.tenant.routes.QM","master");values.put("oms.tenant.routes.BETA","second");new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values)).bind("oms.tenant",org.springframework.boot.context.properties.bind.Bindable.ofInstance(tenants));tenants.validate();assertEquals(new TreeSet<>(Arrays.asList("QM","BETA")),tenants.companies());assertEquals("master",tenants.capture("qm").datasource());}
    @Test void reusedWorkerThreadsOnlySeeTheExplicitCapturedCompany() throws Exception {
        com.oms.common.task.ScopedWorkExecutor executor=new com.oms.common.task.ScopedWorkExecutor("tenant-test");java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(10);java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        try{for(int i=0;i<10;i++){String company=i%2==0?"QM":"BETA",pool=i%2==0?"master":"second";assertTrue(executor.submit(tenants.capture(company),()->{try{assertEquals(pool,DynamicDataSourceContextHolder.peek());}catch(Throwable e){failure.compareAndSet(null,e);}finally{done.countDown();}}));}assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertNull(failure.get());assertNull(DynamicDataSourceContextHolder.peek());}finally{executor.close();}
    }
    @Test void groupNameCannotSelectAPool(){when(routing.getGroupDataSources()).thenReturn(Collections.singletonMap("master",mock(com.baomidou.dynamic.datasource.ds.GroupDataSource.class)));assertThrows(IllegalArgumentException.class,tenants::validate);}
    @Test void nestedScopesRestoreContextEvenWhenWorkFails(){DynamicDataSourceContextHolder.push("outer");assertThrows(IllegalStateException.class,()->tenants.run("QM",()->{assertEquals("master",DynamicDataSourceContextHolder.peek());tenants.run("BETA",()->assertEquals("second",DynamicDataSourceContextHolder.peek()));assertEquals("master",DynamicDataSourceContextHolder.peek());throw new IllegalStateException("expected");}));assertEquals("outer",DynamicDataSourceContextHolder.peek());}
    @Test void transactionCannotChangeCompanyEvenWhenSharingTheSamePool(){tenants.run("QM",()->{TransactionSynchronizationManager.setActualTransactionActive(true);tenants.run("QM",()->assertEquals("master",DynamicDataSourceContextHolder.peek()));assertThrows(IllegalArgumentException.class,()->tenants.run("SHARED",()->fail("company switch")));assertThrows(IllegalArgumentException.class,()->tenants.run("BETA",()->fail("pool switch")));});assertNull(DynamicDataSourceContextHolder.peek());}
    @Test void transactionStartedOutsideTenantScopeCannotSelectAnotherPool(){TransactionSynchronizationManager.setActualTransactionActive(true);assertThrows(IllegalArgumentException.class,()->tenants.run("BETA",()->fail("unbound transaction")));}
    @Test void capturedWorkRejectsReplacedPool(){TenantRouting.Route captured=tenants.capture("QM");routing.getDataSources().put("master",second);when(routing.getDataSource("master")).thenReturn(second);assertThrows(IllegalArgumentException.class,()->captured.run(()->fail("stale pool")));assertNull(DynamicDataSourceContextHolder.peek());}
    @Test void capturedWorkRejectsChangedCompanyMapping(){TenantRouting.Route captured=tenants.capture("QM");tenants.setRoutes(Collections.singletonMap("QM","second"));assertThrows(IllegalArgumentException.class,()->captured.run(()->fail("stale mapping")));}
    DynamicDatasourceInterceptorFilter filter(){DynamicDatasourceInterceptorFilter f=new DynamicDatasourceInterceptorFilter(){@Override public String getCompanyCode(){return "QM";}};ReflectionTestUtils.setField(f,"tenantRouting",tenants);return f;}
    @Test void publicRequestsUseLoginCompanyAndRejectOverrideEvenWithInternalHeader() throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/productInventory");request.addParameter("company_code","qm");MockHttpServletResponse response=new MockHttpServletResponse();filter().doFilter(request,response,(req,res)->{assertEquals("QM",req.getParameter("company_code"));assertEquals("master",DynamicDataSourceContextHolder.peek());});assertNull(DynamicDataSourceContextHolder.peek());
        request.setParameter("company_code","BETA");request.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);MockHttpServletResponse rejected=new MockHttpServletResponse();filter().doFilter(request,rejected,(req,res)->fail("cross-company public request"));assertEquals(400,rejected.getStatus());
    }
    @Test void internalCompanyIsExplicitAndUnknownCompanyCannotFallBack() throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/orderStock/internal/demands");request.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);request.addParameter("company_code","BETA");filter().doFilter(request,new MockHttpServletResponse(),(req,res)->assertEquals("second",DynamicDataSourceContextHolder.peek()));
        request.setParameter("company_code","UNKNOWN");MockHttpServletResponse rejected=new MockHttpServletResponse();filter().doFilter(request,rejected,(req,res)->fail("unknown internal company"));assertEquals(503,rejected.getStatus());assertNull(DynamicDataSourceContextHolder.peek());
    }
    @Test void requestBoundaryClearsContextAfterFailureAndDuplicateIdentityIsRejected() throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/productInventory");DynamicDataSourceContextHolder.push("stale");assertThrows(javax.servlet.ServletException.class,()->filter().doFilter(request,new MockHttpServletResponse(),(req,res)->{assertEquals("master",DynamicDataSourceContextHolder.peek());throw new javax.servlet.ServletException("expected");}));assertNull(DynamicDataSourceContextHolder.peek());
        request.addParameter("company_code",new String[]{"QM","BETA"});MockHttpServletResponse rejected=new MockHttpServletResponse();filter().doFilter(request,rejected,(req,res)->fail("duplicate identity"));assertEquals(400,rejected.getStatus());
    }

    com.oms.common.tenant.TenantCompanyAccess manageQM(){
        com.oms.common.tenant.TenantCompanyAccess access=mock(com.oms.common.tenant.TenantCompanyAccess.class);
        org.springframework.beans.factory.ObjectProvider<com.oms.common.tenant.TenantCompanyAccess> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(access);ReflectionTestUtils.setField(tenants,"companyAccess",provider);tenants.setManaged(Collections.singletonMap("QM",true));return access;
    }
    @Test void nestedCompanyScopeChecksRuntimeSwitchOnlyOnce(){
        com.oms.common.tenant.TenantCompanyAccess access=manageQM();
        tenants.run("QM",()->{for(int i=0;i<50;i++)tenants.run("QM",()->{});tenants.callForContinuation("QM",()->null);});
        verify(access,times(1)).requireEnabled("QM");verify(access,never()).requireExisting(anyString());
    }
    @Test void scanReadsManagedStatesInOneBatchAndKeepsLegacyCompanyOnFailure(){
        com.oms.common.tenant.TenantCompanyAccess access=manageQM();when(access.states(Collections.singleton("QM"))).thenReturn(Collections.singletonMap("QM","DISABLED"));
        assertFalse(tenants.companies().contains("QM"));verify(access,times(1)).states(anyCollection());assertTrue(tenants.continuingCompanies().contains("QM"));
        when(access.states(anyCollection())).thenThrow(new IllegalStateException("redis down"));assertEquals(new TreeSet<>(Arrays.asList("BETA","SHARED")),tenants.companies());
    }
    @Test void capturedNewWorkIsRejectedAfterDisableButReceiptWorkContinues(){
        com.oms.common.tenant.TenantCompanyAccess access=manageQM();TenantRouting.Route queued=tenants.capture("QM");
        doThrow(new IllegalArgumentException("disabled")).when(access).requireEnabled("QM");
        assertThrows(IllegalArgumentException.class,()->queued.run(()->fail("new work")));assertEquals("ok",tenants.callForContinuation("QM",()->"ok"));assertNull(DynamicDataSourceContextHolder.peek());
    }
    @Test void continuingScopeCannotEscalateToNewWorkAfterDisable(){
        com.oms.common.tenant.TenantCompanyAccess access=manageQM();doThrow(new IllegalArgumentException("disabled")).when(access).requireEnabled("QM");
        tenants.callForContinuation("QM",()->{assertThrows(IllegalArgumentException.class,()->tenants.run("QM",()->fail("escalation")));return null;});verify(access).requireExisting("QM");verify(access).requireEnabled("QM");
    }
    @Test void disabledCompanyAllowsWarehouseReceiptButRefusesNewAllocation() throws Exception {
        com.oms.common.tenant.TenantCompanyAccess access=manageQM();doThrow(new IllegalArgumentException("disabled")).when(access).requireEnabled("QM");
        MockHttpServletRequest req=new MockHttpServletRequest("POST","/orderStock/internal/warehouse-outbound");req.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);req.addParameter("company_code","QM");
        filter().doFilter(req,new MockHttpServletResponse(),(request,response)->{});
        req.setRequestURI("/orderStock/internal/warehouse-allocate");MockHttpServletResponse denied=new MockHttpServletResponse();filter().doFilter(req,denied,(request,response)->fail("new allocation"));assertEquals(503,denied.getStatus());
    }

    @Test void allocationScanUsesServiceScopedRouteDiscoveryAndRequiresInternalIdentity() throws Exception {
        MockHttpServletRequest req=new MockHttpServletRequest("POST","/orders/internal/schedule/allocation");req.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);
        filter().doFilter(req,new MockHttpServletResponse(),(request,response)->assertNull(DynamicDataSourceContextHolder.peek()));assertNull(DynamicDataSourceContextHolder.peek());
    }
    @Test void refundReturnInternalCallsUseTheExplicitCompanyRoute() throws Exception {for(String path:Arrays.asList("/refundReturns/internal/12","/refundReturns/internal/pending","/refundReturns/internal/12/acknowledge")){MockHttpServletRequest request=new MockHttpServletRequest("POST",path);request.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);request.addParameter("company_code","BETA");MockHttpServletResponse response=new MockHttpServletResponse();java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();filter().doFilter(request,response,(req,res)->{called.set(true);assertEquals("second",DynamicDataSourceContextHolder.peek());});assertTrue(called.get());assertNull(DynamicDataSourceContextHolder.peek());}}
    @Test void refundReturnCompanyCannotBeSpoofedByPublicRequests() throws Exception {MockHttpServletRequest request=new MockHttpServletRequest("POST","/refundReturns/internal/12");request.addParameter("company_code","BETA");MockHttpServletResponse response=new MockHttpServletResponse();filter().doFilter(request,response,(req,res)->fail("must reject override"));assertEquals(400,response.getStatus());}
    @Test void allocationContextControllerUsesTrustedCompanyAndPublicCannotOverride() throws Exception {
        Class<?> controller=com.oms.order.controller.RefundAllocationController.class;
        String path=controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0]+controller.getMethod("context",String.class,long.class,List.class).getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value()[0];
        MockHttpServletRequest request=new MockHttpServletRequest("POST",path);request.addParameter("company_code","BETA");request.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();
        filter().doFilter(request,new MockHttpServletResponse(),(req,res)->{called.set(true);assertEquals("second",DynamicDataSourceContextHolder.peek());});assertTrue(called.get());assertNull(DynamicDataSourceContextHolder.peek());
        request=new MockHttpServletRequest("POST",path);request.addParameter("company_code","BETA");MockHttpServletResponse rejected=new MockHttpServletResponse();filter().doFilter(request,rejected,(req,res)->fail("public company override"));assertEquals(400,rejected.getStatus());
    }
 @Test void warehouseProcessInternalCallsUseTheirTrustedCompany() throws Exception {for(String path:Arrays.asList("/stockProcess/internal/reserve","/stockProcess/internal/post","/stockProcess/internal/release","/stockProcess/internal/report","/stockProcess/internal/origin","/stockProcess/internal/batches")){MockHttpServletRequest req=new MockHttpServletRequest("POST",path);req.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);req.addParameter("company_code","BETA");java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();filter().doFilter(req,new MockHttpServletResponse(),(r,response)->{called.set(true);assertEquals("second",DynamicDataSourceContextHolder.peek());});assertTrue(called.get());assertNull(DynamicDataSourceContextHolder.peek());}}
 @Test void warehouseProcessCompanyCannotBeSpoofedByPublicRequests() throws Exception {MockHttpServletRequest req=new MockHttpServletRequest("POST","/stockProcess/internal/reserve");req.addParameter("company_code","BETA");MockHttpServletResponse response=new MockHttpServletResponse();filter().doFilter(req,response,(r,res)->fail("untrusted company override"));assertEquals(400,response.getStatus());}
 @Test void messageCompensationUsesTrustedServiceDiscoveryWithoutCompanyFromRequest() throws Exception {
  MockHttpServletRequest req=new MockHttpServletRequest("POST","/messages/internal/compensate");req.addHeader(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);req.addParameter("company_code","UNKNOWN");
  filter().doFilter(req,new MockHttpServletResponse(),(request,response)->assertNull(DynamicDataSourceContextHolder.peek()));assertNull(DynamicDataSourceContextHolder.peek());
  assertNotNull(com.oms.messaging.MessageController.class.getMethod("compensate").getAnnotation(com.ruoyi.common.security.annotation.InnerAuth.class));
  req=new MockHttpServletRequest("GET","/messages");req.addParameter("company_code","BETA");MockHttpServletResponse rejected=new MockHttpServletResponse();filter().doFilter(req,rejected,(r,response)->fail("cross company"));assertEquals(400,rejected.getStatus());
 }
}
