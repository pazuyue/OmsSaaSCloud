package com.oms.order.service;
import org.junit.jupiter.api.*;
import java.util.*;
import com.ruoyi.common.core.domain.R;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RefundGoodsServiceTest {
    RefundServiceTest f;RefundGoodsService goods;long refund,source;
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){f=new RefundServiceTest();f.reset();f.shipped();source=f.db.queryForObject("SELECT id FROM oms_order_shipment_line",Long.class);refund=f.convert(f.ingest(f.source(1,"2.00","SUCCESS")));f.workspace.review("VERIFY",refund,f.review(refund,"CANCEL","REFUSAL"),"test");goods=new RefundGoodsService();goods.refunds=f.refunds;goods.catalog=mock(OrderCatalog.class);goods.returns=mock(RefundReturnClient.class);goods.inventory=mock(OrderWarehouseClient.class);when(goods.catalog.warehouses("VERIFY")).thenReturn(Collections.singletonList(m("wms_simulation_code","V","owner_code","O")));when(goods.inventory.returnBasis(anyString(),anyMap(),anyString())).thenReturn(R.ok(m("unit_cost","2.50","quantity",5)));}
    @AfterEach void cleanup(){f.clear();}
    Map<String,Object> version(){return m("control_version",f.refunds.business("VERIFY",refund,false).get("control_version"),"confirmed",true,"reason","verified","evidence","case-proof");}
    Map<String,Object> input(long quantity){Map<String,Object> p=version();p.putAll(m("shipment_line_id",source,"quantity",quantity,"store_code","V","return_address","TEST-ADDRESS","return_contact","TEST-CONTACT","request_key","SAME_REQUEST_001"));return p;}
    long create(long qty){return number(goods.create("VERIFY",refund,input(qty),"test"),"id");}
    Map<String,Object> plan(long id){return f.db.query("SELECT * FROM oms_refund_return_plan WHERE id=?",ROW,id).get(0);}
    Map<String,Object> command(long id){Map<String,Object> p=version();p.put("plan_version",plan(id).get("control_version"));return p;}
    @Test void explicitSourcePlanNeverUsesRefundAmountOrPlatformNumAndDoesNotDispatch(){long id=create(5);assertEquals(5,number(plan(id),"quantity"));assertEquals("PLANNED",plan(id).get("state"));assertEquals(0,number(plan(id),"accepted_quantity"));verifyNoInteractions(goods.returns);assertEquals(1,goods.report("VERIFY",refund).get("plans") instanceof List?((List<?>)goods.report("VERIFY",refund).get("plans")).size():0);}
    @Test @SuppressWarnings("unchecked") void orderPurchaseReturnViewUsesActualSourceAndSeparatesGiftsAndTenant(){
        long id=create(5);f.db.update("UPDATE oms_refund_return_plan SET accepted_quantity=3,posted_quantity=2 WHERE id=?",id);
        Map<String,Object> order=m("id",f.order,"platform_order_id",f.platform);OrderDocumentInfo.related(f.repo,"VERIFY",order);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)order.get("purchase_returns");assertEquals(1,rows.size());assertEquals(3,number(rows.get(0),"accepted_quantity"));assertEquals(2,number(rows.get(0),"posted_quantity"));assertEquals("件",rows.get(0).get("stock_unit"));assertEquals("987654321012345679",rows.get(0).get("platform_oid"));
        OrderDocumentInfo.related(f.repo,"OTHER",order);assertTrue(((List<?>)order.get("purchase_returns")).isEmpty());
        f.db.update("UPDATE oms_order_line SET stocked_bundle_snapshot='{}' WHERE company_code='VERIFY' AND order_id=?",f.order);OrderDocumentInfo.related(f.repo,"VERIFY",order);assertEquals("套",((Map<?,?>)((List<?>)order.get("purchase_returns")).get(0)).get("stock_unit"));
        f.db.update("UPDATE oms_refund_return_plan SET gift_plan_id=7 WHERE id=?",id);OrderDocumentInfo.related(f.repo,"VERIFY",order);assertTrue(((List<?>)order.get("purchase_returns")).isEmpty());
        f.db.update("UPDATE oms_refund_return_plan SET gift_plan_id=NULL WHERE id=?",id);f.db.update("UPDATE oms_order_line SET gift_plan_id=7 WHERE company_code='VERIFY' AND order_id=?",f.order);OrderDocumentInfo.related(f.repo,"VERIFY",order);assertTrue(((List<?>)order.get("purchase_returns")).isEmpty());
    }
    @Test void sameSourceIsReusedButCannotBeOverwritten(){long id=create(3);assertEquals(id,create(3));assertThrows(IllegalArgumentException.class,()->create(4));assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan",Integer.class));}
    @Test @SuppressWarnings("unchecked") void returnPresentationUsesFrozenShipmentOwnershipAndStockUnit(){
        long id=create(3);
        Map<String,Object> row=((List<Map<String,Object>>)goods.report("VERIFY",refund).get("plans")).get(0);
        assertEquals(source,number(row,"source_id"));assertEquals("件",row.get("stock_unit"));assertEquals("PURCHASE",row.get("item_kind"));assertFalse(text(row,"goods_name").isEmpty());
        f.db.update("UPDATE oms_order_line SET stocked_bundle_snapshot='{}',gift_plan_id=7 WHERE company_code='VERIFY' AND order_id=?",f.order);
        f.db.update("UPDATE oms_refund_return_plan SET gift_plan_id=7 WHERE id=?",id);
        row=((List<Map<String,Object>>)goods.report("VERIFY",refund).get("plans")).get(0);
        assertEquals("套",row.get("stock_unit"));assertEquals("STOCKED_BUNDLE",row.get("source_kind"));assertEquals("GIFT",row.get("item_kind"));
        f.db.update("INSERT INTO oms_order_bundle(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,revision,snapshot,business_type) VALUES ('VERIFY',?,'PARENT','BAG','Frozen bag',1,0,1,'{}','LUCKY_BAG')",f.order);
        long bundle=f.db.queryForObject("SELECT id FROM oms_order_bundle WHERE company_code='VERIFY' AND order_id=? AND platform_oid='PARENT'",Long.class,f.order);
        f.db.update("UPDATE oms_order_line SET stocked_bundle_snapshot=NULL,bundle_id=? WHERE company_code='VERIFY' AND order_id=?",bundle,f.order);
        row=((List<Map<String,Object>>)goods.report("VERIFY",refund).get("plans")).get(0);
        assertEquals("件",row.get("stock_unit"));assertEquals("LUCKY_BAG",row.get("source_kind"));assertEquals("Frozen bag",row.get("parent_goods_name"));
        List<Map<String,Object>> foreign=new ArrayList<>(Collections.singletonList(m("source_line_id",source)));
        RefundReturnPresentation.sources(f.repo,"OTHER",foreign,"source_line_id");assertFalse(foreign.get(0).containsKey("goods_name"));
        List<Map<String,Object>> members=new ArrayList<>(Arrays.asList(m("source_line_id",source,"refund_id",1),m("source_line_id",source,"refund_id",2)));
        RefundReturnPresentation.sources(f.repo,"VERIFY",members,"source_line_id");assertEquals("GIFT",members.get(1).get("item_kind"));assertEquals(2,number(members.get(1),"refund_id"));
    }
    @Test void cannotClaimBeyondActualSourceOrBorrowAnotherCompany(){assertThrows(IllegalArgumentException.class,()->create(6));assertThrows(IllegalArgumentException.class,()->goods.create("OTHER",refund,input(2),"test"));}
    @Test void approvedExchangeAndRefundShareOneSourceCapacity(){f.db.update("DELETE FROM oms_exchange");f.db.update("INSERT INTO oms_exchange(company_code,exchange_sn,channel_id,source_kind,source_modified,dispute_id,payload,payload_hash,source_shipment_line_id,requested_quantity,approved_version,state) VALUES ('VERIFY','HH_CLAIM',1,'INTERNAL',NOW(),'123456','{}','hash',?,3,1,'APPROVED')",source);assertThrows(IllegalArgumentException.class,()->create(3));assertNotNull(create(2));}
    @Test void unsentCancellationAndWaiverDoNotPretendReceived(){long id=create(5);Map<String,Object> stop=command(id);goods.action("VERIFY",refund,id,0,"cancel",stop,"test");assertEquals("STOPPED",plan(id).get("state"));Map<String,Object> waive=command(id);waive.put("quantity",5);goods.action("VERIFY",refund,id,0,"waive",waive,"test");assertEquals(0,number(plan(id),"accepted_quantity"));assertEquals(5,number(plan(id),"waived_quantity"));goods.finish("VERIFY",refund,version(),"test");assertEquals("COMPLETED",f.refunds.business("VERIFY",refund,false).get("state"));verifyNoInteractions(goods.returns);}
    @Test void unfinishedReturnCannotBeFinishedOrReviewedIntoKeep(){long id=create(5);assertThrows(IllegalArgumentException.class,()->goods.finish("VERIFY",refund,version(),"test"));assertThrows(IllegalArgumentException.class,()->f.workspace.review("VERIFY",refund,f.review(refund,"COMPENSATION","KEEP"),"test"));}
    @Test void duplicateReturnPlanConsumesActualShipmentEligibility(){long id=create(5);ExchangeServiceTest x=new ExchangeServiceTest();x.repo=f.repo;ExchangeService e=new ExchangeService();org.springframework.test.util.ReflectionTestUtils.setField(e,"repository",f.repo);long line=f.db.queryForObject("SELECT id FROM oms_order_line",Long.class);assertEquals(0,e.eligible("VERIFY",m("id",99999,"order_line_id",line,"source_shipment_line_id",source,"fulfillment_mode","RETURN")));}
    @Test void noRecoveryRequiresEvidenceAndCannotBypassExistingArrangements(){Map<String,Object> v=version();v.put("evidence","");assertThrows(IllegalArgumentException.class,()->goods.waiveAll("VERIFY",refund,v,"test"));create(5);assertThrows(IllegalArgumentException.class,()->goods.waiveAll("VERIFY",refund,version(),"test"));}
    @Test void dispatchPreservesStableRequestAndRequiresWarehouseCompletionBeforeFinish(){long id=create(5);Map<String,Object> a=m("id",1,"return_sn","THK_TEST","quantity",5,"accepted_quantity",0,"posted_quantity",0,"pending_review_count",0,"documents",Collections.singletonList(m("id",1,"cancel_state","NONE","receipt_state","WAITING")));when(goods.returns.prepare(eq("VERIFY"),eq(id),anyMap(),anyString())).thenReturn(R.ok(a));when(goods.returns.detail(eq("VERIFY"),eq(id),anyString())).thenReturn(R.ok(a));goods.action("VERIFY",refund,id,0,"dispatch",command(id),"test");assertEquals("ACTIVE",plan(id).get("state"));assertThrows(IllegalArgumentException.class,()->goods.finish("VERIFY",refund,version(),"test"));a.put("accepted_quantity",5);a.put("posted_quantity",5);a.put("documents",Collections.singletonList(m("id",1,"cancel_state","NONE","receipt_state","COMPLETED")));goods.refresh("VERIFY",refund);goods.finish("VERIFY",refund,version(),"test");assertEquals("DONE",f.refunds.business("VERIFY",refund,false).get("goods_state"));a.put("pending_review_count",1);goods.refresh("VERIFY",refund);assertEquals("WAIT_CHECK",f.refunds.business("VERIFY",refund,false).get("goods_state"));assertEquals("SUCCESS",f.refunds.business("VERIFY",refund,false).get("funds_state"));}
    @Test void unshippedCancellationUsesFrozenIdempotentStockCommand(){f.db.update("DELETE FROM oms_order_shipment_line");f.db.update("DELETE FROM oms_order_shipment");long line=f.db.queryForObject("SELECT id FROM oms_order_line",Long.class);Map<String,Object> review=f.review(refund,"CANCEL","CANCEL");review.put("cancel_line_id",line);review.put("cancel_quantity",2);f.workspace.review("VERIFY",refund,review,"test");when(goods.inventory.adjust(eq("VERIFY"),anyMap(),anyString())).thenThrow(new IllegalArgumentException("temporary"));assertThrows(IllegalArgumentException.class,()->goods.cancelUnshipped("VERIFY",refund,version(),"test"));assertNotNull(f.refunds.business("VERIFY",refund,false).get("cancel_command"));when(goods.inventory.adjust(eq("VERIFY"),anyMap(),anyString())).thenReturn(R.ok(m("state","COMPLETED","lines",Collections.singletonList(m("platform_oid","987654321012345679","sku_sn","A","cancelled_quantity",2,"reserved_quantity",3)))));goods.cancelUnshipped("VERIFY",refund,version(),"test");assertEquals(2,number(f.refunds.business("VERIFY",refund,false),"cancelled_quantity"));assertEquals("DONE",f.refunds.business("VERIFY",refund,false).get("goods_state"));}
    @Test void shippedGoodsCannotBeCanceledAsUnshipped(){Map<String,Object> review=f.review(refund,"CANCEL","CANCEL");review.put("cancel_line_id",f.db.queryForObject("SELECT id FROM oms_order_line",Long.class));review.put("cancel_quantity",1);assertThrows(IllegalArgumentException.class,()->f.workspace.review("VERIFY",refund,review,"test"));verify(goods.inventory,never()).adjust(anyString(),anyMap(),anyString());}
    @Test void omsShipmentCannotExceedTheInventoryOutboundSource(){when(goods.inventory.returnBasis(anyString(),anyMap(),anyString())).thenReturn(R.ok(m("unit_cost","2.50","quantity",2)));assertThrows(IllegalArgumentException.class,()->create(3));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan",Integer.class));verifyNoInteractions(goods.returns);}
    @Test void stopPlanUsesFreshDocumentVersionsAndDoesNotResolvePendingReceipt(){
        long id=create(5);Map<String,Object> old=m("id",1,"control_version",4,"cancel_state","CONFIRMED","receipt_state","PARTIAL"),next=m("id",2,"control_version",8,"cancel_state","NONE","receipt_state","WAITING");
        Map<String,Object> actual=m("id",2,"quantity",5,"accepted_quantity",2,"posted_quantity",2,"pending_review_count",1,"documents",Arrays.asList(next,old));
        f.db.update("UPDATE oms_refund_return_plan SET result_json=?,state='WAIT_REVIEW' WHERE id=?",encode(actual),id);
        when(goods.returns.detail(eq("VERIFY"),eq(id),anyString())).thenReturn(R.ok(actual));
        when(goods.returns.document(eq("VERIFY"),eq(id),eq(2L),eq("cancel"),anyMap(),anyString())).thenAnswer(call->{Map<String,Object> body=call.getArgument(4);assertEquals(8,number(body,"document_version"));assertEquals("test",body.get("actor"));next.put("cancel_state","CONFIRMED");return R.ok(actual);});
        goods.action("VERIFY",refund,id,0,"stop",command(id),"test");
        assertEquals("WAIT_REVIEW",plan(id).get("state"));assertEquals(2,number(plan(id),"posted_quantity"));verify(goods.returns,never()).document(anyString(),anyLong(),eq(1L),anyString(),anyMap(),anyString());
        assertThrows(IllegalArgumentException.class,()->goods.finish("VERIFY",refund,version(),"test"));
    }
    @Test void platformClosureCannotDiscardOpenReturnArrangementsOrPostedFacts(){
        f.reset();f.shipped();source=f.db.queryForObject("SELECT id FROM oms_order_shipment_line",Long.class);refund=f.convert(f.ingest(f.source(1,"2.00","WAIT_SELLER_AGREE")));f.workspace.review("VERIFY",refund,f.review(refund,"CANCEL","RETURN"),"test");goods.refunds=f.refunds;long id=create(5);Map<String,Object> closed=f.source(2,"2.00","CLOSED");f.ingest(closed);assertEquals("CLOSED",f.refunds.platform("VERIFY",number(f.refunds.business("VERIFY",refund,false),"platform_refund_id"),false).get("platform_status"));assertEquals("CLOSED",f.refunds.business("VERIFY",refund,false).get("funds_state"));
        assertThrows(IllegalArgumentException.class,()->goods.closeWithdrawn("VERIFY",refund,version(),"test"));assertThrows(IllegalArgumentException.class,()->goods.finish("VERIFY",refund,version(),"test"));goods.action("VERIFY",refund,id,0,"stop",command(id),"test");goods.closeWithdrawn("VERIFY",refund,version(),"test");assertEquals("CLOSED",f.refunds.business("VERIFY",refund,false).get("state"));assertEquals(5,number(plan(id),"waived_quantity"));assertEquals(0,number(plan(id),"posted_quantity"));assertEquals("",OrderExchangeGuard.reason(f.repo,"VERIFY",f.order,false));verify(goods.inventory,never()).adjust(anyString(),anyMap(),anyString());
    }
    @Test @SuppressWarnings("unchecked") void progressContextKeepsFrozenUnitsAndUnresolvedWarehouseWork(){
        long id=create(5);f.db.update("UPDATE oms_order_line SET stocked_bundle_snapshot='{}' WHERE company_code='VERIFY' AND order_id=?",f.order);
        Map<String,Object> actual=m("pending_review_count",1,"documents",Collections.singletonList(m("return_sn","THK_PROGRESS","dispatch_state","UNKNOWN","cancel_state","NONE","receipt_state","WAITING")));
        f.db.update("UPDATE oms_refund_return_plan SET result_json=? WHERE id=?",encode(actual),id);
        Map<String,Object> detail=f.workspace.detail("VERIFY",refund),context=(Map<String,Object>)detail.get("progress_context");
        Map<String,Object> row=((List<Map<String,Object>>)context.get("plans")).get(0);
        assertEquals("套",row.get("stock_unit"));assertEquals(1,number(row,"pending_count"));assertEquals(1,number(row,"active_documents"));
        assertEquals(true,row.get("has_document"));assertFalse(row.containsKey("result_json"));assertEquals("UNKNOWN",row.get("dispatch_state"));
        assertEquals(0,number(plan(id),"posted_quantity"));assertEquals("SUCCESS",detail.get("funds_state"));
    }

 @Test void mixedScopeCannotCreateAnUnapprovedSeparateReturn(){f.db.update("UPDATE oms_refund SET goods_action='MIXED' WHERE id=?",refund);Map<String,Object> proposed=input(5);proposed.put("action","RETURN");IllegalArgumentException e=assertThrows(IllegalArgumentException.class,()->goods.create("VERIFY",refund,proposed,"test"));assertTrue(e.getMessage().contains("分项退回必须沿用已审核任务"));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan",Integer.class));verifyNoInteractions(goods.returns);}
}
