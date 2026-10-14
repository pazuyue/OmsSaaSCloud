package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderImmediateWorkTest {
    OrderShipmentServiceTest fixture=new OrderShipmentServiceTest();
    @BeforeAll static void setup(){OrderShipmentServiceTest.setup();}
    @BeforeEach void seed(){fixture.seed();}
    @AfterEach void clear(){DynamicDataSourceContextHolder.clear();}
    @Test void rollbackDoesNotWakeWorkerAndCommittedChangeIsDurable(){
        OrderImmediateWork work=mock(OrderImmediateWork.class);ObjectProvider<OrderImmediateWork> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(work);ReflectionTestUtils.setField(fixture.repository,"immediate",provider);TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(fixture.db.getDataSource()));DynamicDataSourceContextHolder.push("tenant");
        tx.execute(s->{fixture.repository.shipmentChanged("VERIFY",998);s.setRollbackOnly();return null;});verifyNoInteractions(work);assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM oms_order_sales_document_sync",Integer.class));
        tx.execute(s->{fixture.repository.shipmentChanged("VERIFY",998);verifyNoInteractions(work);return null;});verify(work).salesChanged("VERIFY","tenant",998);assertEquals(1,fixture.db.queryForObject("SELECT dirty_version FROM oms_order_sales_document_sync",Integer.class));
    }
    @Test void immediateWorkUsesCapturedTenantWithoutWaitingForBusinessCompletion() throws Exception {
        OrderImmediateWork work=new OrderImmediateWork();OrderShipmentReceiptService receipt=mock(OrderShipmentReceiptService.class);DynamicRoutingDataSource routing=mock(DynamicRoutingDataSource.class);DataSource pool=fixture.db.getDataSource();when(routing.getDataSources()).thenReturn(Collections.singletonMap("tenant",pool));when(routing.getDataSource("tenant")).thenReturn(pool);
        com.oms.common.tenant.TenantRouting tenants=new com.oms.common.tenant.TenantRouting();ReflectionTestUtils.setField(tenants,"dataSource",routing);tenants.setRoutes(Collections.singletonMap("VERIFY","tenant"));tenants.validate();ReflectionTestUtils.setField(work,"tenantRouting",tenants);ReflectionTestUtils.setField(work,"receipts",receipt);CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1),done=new CountDownLatch(1);
        when(receipt.ready("VERIFY",7)).thenAnswer(i->{assertEquals("tenant",DynamicDataSourceContextHolder.peek());started.countDown();try{assertTrue(finish.await(5,TimeUnit.SECONDS));return Collections.emptyMap();}finally{done.countDown();}});
        try{DynamicDataSourceContextHolder.push("request-alias");assertTrue(work.receiptReady("VERIFY",7));assertTrue(started.await(5,TimeUnit.SECONDS));assertEquals(1,done.getCount());assertEquals("request-alias",DynamicDataSourceContextHolder.peek());finish.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));}finally{finish.countDown();work.close();}
    }
}
