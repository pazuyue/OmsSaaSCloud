package com.oms.order.service;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.common.tenant.TenantRouting;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundSyncWakeupTest {
    TenantRouting tenants;
    @BeforeEach void setup(){
        DynamicRoutingDataSource routing=mock(DynamicRoutingDataSource.class);
        DataSource pool=mock(DataSource.class);
        when(routing.getDataSources()).thenReturn(Collections.singletonMap("master",pool));
        when(routing.getDataSource("master")).thenReturn(pool);
        tenants=new TenantRouting();ReflectionTestUtils.setField(tenants,"dataSource",routing);
        Map<String,String> routes=new HashMap<>();routes.put("QM","master");routes.put("OTHER","master");
        tenants.setRoutes(routes);tenants.validate();
    }
    @AfterEach void clear(){TenantRouting.clearContext();}
    static void await(CountDownLatch latch){
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("worker timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    void requestDuringScan(boolean failFirst) throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),again=new CountDownLatch(1);
        AtomicInteger runs=new AtomicInteger(),running=new AtomicInteger(),maxRunning=new AtomicInteger();
        AtomicBoolean routed=new AtomicBoolean(true);
        RefundSync sync=new RefundSync(){@Override void run(String company){
            maxRunning.accumulateAndGet(running.incrementAndGet(),Math::max);
            try{
                if(!"QM".equals(company)||!"master".equals(DynamicDataSourceContextHolder.peek()))routed.set(false);
                if(runs.incrementAndGet()==1){entered.countDown();await(release);if(failFirst)throw new IllegalStateException("scan failure");}
                else again.countDown();
            }finally{running.decrementAndGet();}
        }};
        sync.tenants=tenants;
        try{
            assertTrue(sync.kick("QM"));assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(int i=0;i<25;i++)assertFalse(sync.kick("QM"));
            release.countDown();assertTrue(again.await(5,TimeUnit.SECONDS),"review wakeup must trigger another scan");
            assertEquals(2,runs.get());assertEquals(1,maxRunning.get());assertTrue(routed.get());
            assertNull(DynamicDataSourceContextHolder.peek());
        }finally{release.countDown();sync.close();}
    }
    @Test void reviewDuringRuleSaveScanIsRetainedAndCoalesced() throws Exception {requestDuringScan(false);}
    @Test void failedScanStillRunsTheRetainedReviewRequest() throws Exception {requestDuringScan(true);}
    @Test void companiesSharingAPoolHaveIndependentWakeups() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),other=new CountDownLatch(1);
        RefundSync sync=new RefundSync(){@Override void run(String company){
            if("QM".equals(company)){entered.countDown();await(release);}else other.countDown();
        }};sync.tenants=tenants;
        try{assertTrue(sync.kick("QM"));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue(sync.kick("OTHER"));assertTrue(other.await(5,TimeUnit.SECONDS));
        }finally{release.countDown();sync.close();}
    }
}
