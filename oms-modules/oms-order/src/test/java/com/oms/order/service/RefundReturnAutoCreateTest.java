package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.common.core.domain.R;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RefundReturnAutoCreateTest {
    RefundServiceTest f;RefundGoodsService goods;RefundParcelPolicy policy;RefundReturnAutoCreate auto;long refund,source;int serial;
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){
        f=new RefundServiceTest();f.reset();f.shipped();f.db.update("DELETE FROM oms_refund_parcel_config");f.db.update("DELETE FROM oms_exchange");source=f.db.queryForObject("SELECT id FROM oms_order_shipment_line",Long.class);
        goods=new RefundGoodsService();goods.refunds=f.refunds;goods.catalog=mock(OrderCatalog.class);goods.inventory=mock(OrderWarehouseClient.class);goods.returns=mock(RefundReturnClient.class);
        when(goods.catalog.channels("VERIFY")).thenReturn(Collections.singletonList(m("channel_id",1,"channel_name","Test shop","channel_type","TM","enabled",1)));when(goods.catalog.warehouses("VERIFY")).thenReturn(Collections.singletonList(m("wms_simulation_code","V")));
        when(goods.inventory.returnBasis(eq("VERIFY"),anyMap(),anyString())).thenReturn(R.ok(m("quantity",5,"unit_cost","2.50")));
        when(goods.returns.parcelCapabilities(eq("VERIFY"),anyString())).thenReturn(R.ok(Collections.singletonList(m("store_code","V","usable",1))));
        policy=new RefundParcelPolicy();policy.refunds=f.refunds;policy.catalog=goods.catalog;policy.returns=goods.returns;
        auto=new RefundReturnAutoCreate();auto.refunds=f.refunds;auto.policies=policy;auto.goods=goods;auto.bundles=new RefundBundleReturns();auto.bundles.refunds=f.refunds;auto.bundles.goods=goods;f.workspace.autoCreate=auto;
        configure(true);refund=createRefund(2);
    }
    @AfterEach void cleanup(){f.clear();}
    void configure(boolean enabled){Map<String,Object> p=policy.get("VERIFY",1);p.putAll(m("enabled",0,"auto_enabled",0,"auto_dispatch",0,"auto_create",enabled?1:0,"create_store_code","V","create_return_address","Test address","create_return_contact","Test contact"));policy.save("VERIFY",1,p,"test");}
    long createRefund(int qty){Map<String,Object> s=f.source(1,"2.00","SUCCESS"),p=parse(text(s,"jdp_response"));String id="900719925474"+(1100+serial++);p.putAll(m("refund_id",id,"has_good_return",true,"dispute_type","REFUND_AND_RETURN","num",qty));s.put("refund_id",id);s.put("jdp_response",encode(m("refund",p)));return f.convert(f.ingest(s));}
    void review(long id){f.workspace.review("VERIFY",id,f.review(id,"CANCEL","RETURN"),"test");}
    void run(){auto.scan("VERIFY");}
    Map<String,Object> task(){return auto.task("VERIFY",refund,false);}
    int plans(){return f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan WHERE gift_plan_id IS NULL",Integer.class);}
    @Test void onlyFutureReviewsEnqueueAndNeitherReviewNorCreationDispatches(){
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_return_auto_task",Integer.class));review(refund);assertEquals("PENDING",task().get("state"));assertEquals(0,plans());verifyNoInteractions(goods.inventory);
        run();assertEquals("DONE",task().get("state"));assertEquals(1,plans());assertEquals(2,f.db.queryForObject("SELECT quantity FROM oms_refund_return_plan",Integer.class));assertEquals("WAIT_RETURN",f.refunds.business("VERIFY",refund,false).get("goods_state"));
        verify(goods.returns,never()).prepare(anyString(),anyLong(),anyMap(),anyString());assertEquals(0,f.db.queryForObject("SELECT accepted_quantity+posted_quantity FROM oms_refund_return_plan",Integer.class));
    }
    @Test void repeatedScansAndExplicitReReviewNeverDuplicateOrExpandExistingPlans(){review(refund);run();run();review(refund);run();assertEquals(1,plans());assertEquals("DONE",task().get("state"));}
    @Test void disabledSwitchDoesNotBackfillAlreadyApprovedRefunds(){configure(false);review(refund);assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_return_auto_task",Integer.class));configure(true);run();assertEquals(0,plans());}
    @Test void disabledAfterEnqueueStopsTheWork(){review(refund);configure(false);run();assertEquals("CANCELED",task().get("state"));assertEquals(0,plans());}
    @Test void changedRulesRequireExplicitRetryAndRefreshSnapshot(){review(refund);configure(true);run();assertEquals("MANUAL",task().get("state"));auto.retry("VERIFY",refund,m("control_version",f.refunds.business("VERIFY",refund,false).get("control_version")));run();assertEquals(1,plans());}
    @Test void ambiguousShipmentsDoNotGuessEvenWhenOneSourceCouldFulfilQuantity(){review(refund);f.db.update("INSERT INTO oms_order_shipment_line(company_code,shipment_id,order_line_id,platform_oid,sku_sn,goods_name,quantity,shipped_quantity,allocation_id,stock_source_id,store_code,batch_id,source_type) SELECT company_code,shipment_id,order_line_id,platform_oid,sku_sn,goods_name,quantity,shipped_quantity,allocation_id+1,stock_source_id,store_code,batch_id+1,source_type FROM oms_order_shipment_line WHERE id=?",source);run();assertEquals("MANUAL",task().get("state"));assertEquals(0,plans());verifyNoInteractions(goods.inventory);}
    @Test void bundlesAndInvalidQuantitiesRemainManual(){review(refund);f.db.update("UPDATE oms_order_line SET bundle_id=123 WHERE order_id=?",f.order);run();assertEquals("MANUAL",task().get("state"));assertEquals(0,plans());}
    @Test void cannotCreateAboveShippedOrExchangeClaimedQuantity(){refund=createRefund(4);review(refund);f.db.update("INSERT INTO oms_exchange(company_code,exchange_sn,channel_id,source_kind,source_modified,dispute_id,payload,payload_hash,source_shipment_line_id,requested_quantity,approved_version,state) VALUES ('VERIFY','HH_AUTO',1,'INTERNAL',NOW(),'123456','{}','hash',?,3,1,'APPROVED')",source);run();assertEquals("MANUAL",task().get("state"));assertEquals(0,plans());}
    @Test void staleReviewNeverCreatesAndDoesNotBorrowOtherTenantWork(){review(refund);f.db.update("UPDATE oms_platform_refund SET version=version+1 WHERE id=?",f.refunds.business("VERIFY",refund,false).get("platform_refund_id"));auto.scan("OTHER");assertEquals("PENDING",task().get("state"));run();assertEquals("MANUAL",task().get("state"));assertEquals(0,plans());}
    @Test void stoppedShopCannotRunAutomaticCreation(){review(refund);when(goods.catalog.channels("VERIFY")).thenReturn(Collections.singletonList(m("channel_id",1,"enabled",0)));run();assertEquals("CANCELED",task().get("state"));assertEquals(0,plans());}
    @Test void transientFailureRetriesBoundedlyAndExhaustionBecomesVisibleManualTask(){review(refund);when(goods.inventory.returnBasis(anyString(),anyMap(),anyString())).thenThrow(new IllegalStateException("remote unavailable"));run();assertEquals("RETRY",task().get("state"));for(int i=0;i<4;i++){f.db.update("UPDATE oms_refund_return_auto_task SET next_attempt=NOW()");run();}assertEquals("MANUAL",task().get("state"));assertEquals(5,number(task(),"attempts"));assertEquals(0,plans());}
    @Test void expiredLeaseIsRecoverableAndConcurrentWorkersCreateOnlyOnce() throws Exception {
        review(refund);f.db.update("UPDATE oms_refund_return_auto_task SET lease_token='old',lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND)");ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{Callable<Void> work=()->{start.await();auto.process("VERIFY",refund,Collections.singleton(1L));return null;};Future<Void> a=workers.submit(work),b=workers.submit(work);start.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);assertEquals(1,plans());assertEquals("DONE",task().get("state"));}finally{workers.shutdownNow();}
    }
    @Test void differentRefundsCompeteForOneSourceWithoutOverReturning() throws Exception {
        refund=createRefund(4);long other=createRefund(4);review(refund);review(other);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{Future<?> a=workers.submit(()->auto.process("VERIFY",refund,Collections.singleton(1L))),b=workers.submit(()->auto.process("VERIFY",other,Collections.singleton(1L)));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);assertEquals(1,plans());assertEquals(4,f.db.queryForObject("SELECT SUM(quantity) FROM oms_refund_return_plan",Integer.class));assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_return_auto_task WHERE state='MANUAL'",Integer.class));}finally{workers.shutdownNow();}
    }
    @Test void statusIsDecoratedInOneBatchAndManualTaskCanBeFiltered(){review(refund);f.db.update("UPDATE oms_refund_return_auto_task SET state='MANUAL',message='核对来源'");Map<String,Object> result=f.refunds.businessList("VERIFY",Collections.singletonMap("task","ISSUE"));assertEquals(1,number(result,"total"));assertTrue(f.workspace.detail("VERIFY",refund).containsKey("return_auto"));}
    @Test void configRequiresExplicitWarehouseAndAddressAndHasIndependentSwitch(){
        Map<String,Object> p=policy.get("VERIFY",1);p.put("create_return_address","");assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,p,"test"));p.put("create_return_address","Address");p.put("create_store_code","OTHER");assertThrows(IllegalArgumentException.class,()->policy.save("VERIFY",1,p,"test"));
        assertEquals(1,number(policy.get("VERIFY",1),"auto_create"));assertEquals(0,number(policy.get("VERIFY",1),"enabled"));assertEquals(1,number(policy.list("VERIFY",Collections.singletonMap("autoCreate","1")),"total"));
    }
    @Test void sourceQuantityMustBePositiveAndNotExceedOriginalShipment(){
        refund=createRefund(6);review(refund);run();assertEquals("MANUAL",task().get("state"));assertEquals(0,plans());
        f.db.update("UPDATE oms_platform_refund SET platform_quantity=0 WHERE id=?",f.refunds.business("VERIFY",refund,false).get("platform_refund_id"));auto.retry("VERIFY",refund,m("control_version",f.refunds.business("VERIFY",refund,false).get("control_version")));run();assertEquals("MANUAL",task().get("state"));assertEquals(0,plans());
    }
    @Test void catalogFailureBecomesRetryWithoutBlockingOtherRefundMaintenance(){review(refund);when(goods.catalog.channels("VERIFY")).thenThrow(new IllegalStateException("catalog unavailable"));assertDoesNotThrow(()->run());assertEquals("RETRY",task().get("state"));assertEquals(0,plans());}
    @Test void completedGoodsDoNotRemainInTheAutomaticCreationIssueView(){review(refund);f.db.update("UPDATE oms_refund_return_auto_task SET state='MANUAL'");f.db.update("UPDATE oms_refund SET goods_state='DONE',state='COMPLETED' WHERE id=?",refund);assertEquals(0,number(f.refunds.businessList("VERIFY",Collections.singletonMap("task","ISSUE")),"total"));}
    @Test void manualCreationDuringPreparationWinsAndRemoteCallsHoldNoBusinessTransaction(){
        review(refund);java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean();
        when(goods.inventory.returnBasis(eq("VERIFY"),anyMap(),anyString())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());if(once.compareAndSet(false,true))goods.create("VERIFY",refund,m("control_version",f.refunds.business("VERIFY",refund,false).get("control_version"),"shipment_line_id",source,"quantity",1,"store_code","V","return_address","Manual address","return_contact","Manual contact","confirmed",true,"request_key","MANUAL_RETURN_001","reason","Manual scope","evidence","Manual proof"),"manual");return R.ok(m("quantity",5,"unit_cost","2.50"));});
        run();assertEquals("DONE",task().get("state"));assertEquals(1,plans());assertEquals("MANUAL_RETURN_001",f.db.queryForObject("SELECT request_key FROM oms_refund_return_plan",String.class));assertEquals(1,f.db.queryForObject("SELECT quantity FROM oms_refund_return_plan",Integer.class));
    }
    void lucky(){long stock=f.db.queryForObject("SELECT stock_source_id FROM oms_order_shipment_line WHERE id=?",Long.class,source);Map<String,Object> snapshot=m("business_type","LUCKY_BAG","quantity",2,"units",Arrays.asList(m("unit_id",801,"unit",1,"components",Arrays.asList(m("sku_sn","A","quantity",1,"stock_source_id",stock))),m("unit_id",802,"unit",2,"components",Arrays.asList(m("sku_sn","A","quantity",1,"stock_source_id",stock)))));long parent=f.repo.insert("INSERT INTO oms_order_bundle(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,revision,snapshot,business_type) VALUES ('VERIFY',?,'987654321012345679','FD','Test bag',2,20,1,?,'LUCKY_BAG')",f.order,encode(snapshot));f.db.update("UPDATE oms_order_line SET bundle_id=?,component_quantity=0 WHERE order_id=?",parent,f.order);f.db.update("DELETE FROM oms_refund_lucky_unit_claim");}
    @Test void allOriginalBagsAreAutomaticallyExpandedAndRetriesKeepTheirSources(){lucky();review(refund);run();assertEquals("DONE",task().get("state"));assertEquals(1,plans());assertEquals(2,f.db.queryForObject("SELECT SUM(quantity) FROM oms_refund_return_plan",Long.class));assertEquals(2,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_lucky_unit_claim",Long.class));run();assertEquals(1,plans());}
    @Test void partialBagApplicationRequiresExplicitHumanUnitSelection(){lucky();refund=createRefund(1);review(refund);run();assertEquals("MANUAL",task().get("state"));assertTrue(task().get("message").toString().contains("选择原袋"));assertEquals(0,plans());}
}
