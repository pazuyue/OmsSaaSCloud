package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundBusinessListTest {
    RefundServiceTest f;int next;
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){f=new RefundServiceTest();f.reset();next=1;}
    @AfterEach void cleanup(){f.db.update("DELETE FROM oms_order_bundle WHERE company_code='VERIFY' AND order_id=?",f.order);f.clear();}
    long refund(){Map<String,Object> source=f.source(1,"2.00","WAIT_SELLER_AGREE"),p=parse(text(source,"jdp_response"));String id="900719925474"+(1000+next++);p.put("refund_id",id);source.put("refund_id",id);source.put("jdp_response",encode(m("refund",p)));return f.convert(f.ingest(source));}
    long plan(String company,long refund,boolean gift,long quantity,long accepted,long posted){return f.repo.insert("INSERT INTO oms_refund_return_plan(company_code,refund_id,shipment_line_id,shipment_id,shipment_sn,sku_sn,quantity,store_code,return_address,return_contact,package_ids,return_mode,accepted_quantity,posted_quantity,gift_plan_id) VALUES (?,?,?,1,'XS_LIST',?,?,'V','TEST','TEST','[]','RETURN',?,?,?)",company,refund,next++,(gift?"G":"A"),quantity,accepted,posted,gift?7:null);}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,String> query){return (List<Map<String,Object>>)f.refunds.businessList("VERIFY",query).get("rows");}
    @SuppressWarnings("unchecked") Map<String,Object> object(Map<String,Object> row,String key){return (Map<String,Object>)row.get(key);}

    @Test void applicationProductsPreservePlatformIdentityAndExcludeGiftAndReplacement(){
        long id=refund();f.db.update("UPDATE oms_order_line SET cancelled_quantity=quantity WHERE company_code='VERIFY' AND order_id=?",f.order);
        f.db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,gift_plan_id) VALUES ('VERIFY',?,'987654321012345679','G','gift',2,0,7)",f.order);
        f.db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,exchange_id) VALUES ('VERIFY',?,'987654321012345679','B','replacement',5,0,7)",f.order);
        Map<String,Object> row=rows(Collections.emptyMap()).get(0);List<?> products=(List<?>)row.get("application_products");
        assertEquals(id,number(row,"id"));assertEquals(1,number(row,"platform_quantity"));assertEquals(1,products.size());assertEquals("fixture",((Map<?,?>)products.get(0)).get("goods_name"));assertFalse((Boolean)row.get("gift_pending"));
    }
    @Test void purchaseAndGiftProgressAreSeparateAndNeverBorrowOtherRefundOrTenant(){
        long first=refund(),second=refund();plan("VERIFY",first,false,3,2,1);plan("VERIFY",first,true,1,1,1);plan("VERIFY",second,true,7,0,0);plan("OTHER",first,true,99,0,0);
        Map<String,Object> row=rows(Collections.singletonMap("number",text(f.refunds.business("VERIFY",first,false),"refund_sn"))).get(0);
        assertEquals(3,number(object(row,"purchase_progress"),"return_quantity"));assertEquals(1,number(object(row,"gift_progress"),"return_quantity"));assertEquals(4,number(object(row,"return_progress"),"return_quantity"));assertFalse((Boolean)row.get("gift_pending"));
        assertEquals(0,number(f.refunds.businessList("OTHER",Collections.emptyMap()),"total"));
    }
    @Test void giftTaskIsFilteredBeforePaginationAndCanRemainAfterBusinessCompletion(){
        long older=refund();plan("VERIFY",older,true,2,0,0);long newer=refund();f.db.update("UPDATE oms_refund SET state='COMPLETED',review_state='APPROVED',goods_action='KEEP',goods_state='DONE',funds_state='SUCCESS' WHERE company_code='VERIFY'");
        Map<String,String> query=new HashMap<>();query.put("task","GIFT");query.put("pageSize","1");
        assertEquals(1,number(f.refunds.businessList("VERIFY",query),"total"));assertEquals(older,number(rows(query).get(0),"id"));query.put("pageNum","2");assertTrue(rows(query).isEmpty());assertEquals(1,number(f.refunds.businessList("VERIFY",query),"total"));
        assertEquals(newer,number(rows(Collections.singletonMap("task","DONE")).get(0),"id"));
    }
    @Test void pendingReceiptAndUnpostedStockAreBothGiftTasks(){
        long id=refund(),p=plan("VERIFY",id,true,1,1,1);f.db.update("UPDATE oms_refund_return_plan SET result_json=? WHERE id=?",encode(m("pending_review_count",1)),p);
        Map<String,Object> row=rows(Collections.singletonMap("task","GIFT")).get(0);assertTrue((Boolean)row.get("gift_pending"));assertEquals(1,number(object(row,"gift_progress"),"pending_count"));assertEquals(1,rows(Collections.singletonMap("task","ISSUE")).size());
        f.db.update("UPDATE oms_refund_return_plan SET result_json=NULL,posted_quantity=0 WHERE id=?",p);assertTrue((Boolean)rows(Collections.singletonMap("task","GIFT")).get(0).get("gift_pending"));
        f.db.update("UPDATE oms_refund_return_plan SET posted_quantity=1 WHERE id=?",p);assertTrue(rows(Collections.singletonMap("task","GIFT")).isEmpty());
    }
    @Test void timeAndStateFiltersUseServerPaginationAndValidateInput(){
        long first=refund(),second=refund();f.db.update("UPDATE oms_refund SET create_time='2026-10-20 23:59:59',funds_state='SUCCESS' WHERE id=?",first);f.db.update("UPDATE oms_refund SET create_time='2026-10-21 00:00:00' WHERE id=?",second);
        Map<String,String> query=new HashMap<>();query.put("createdFrom","2026-10-20");query.put("createdTo","2026-10-20");query.put("fundsState","SUCCESS");assertEquals(first,number(rows(query).get(0),"id"));assertEquals(1,number(f.refunds.businessList("VERIFY",query),"total"));
        query.put("createdFrom","2026-10-22");assertThrows(IllegalArgumentException.class,()->rows(query));query.put("createdFrom","bad");assertThrows(IllegalArgumentException.class,()->rows(query));assertThrows(IllegalArgumentException.class,()->rows(Collections.singletonMap("fundsState","SQL")));assertThrows(IllegalArgumentException.class,()->rows(Collections.singletonMap("task","UNKNOWN")));
        assertEquals(2,rows(Collections.singletonMap("task","REVIEW")).size());
    }
    @Test void bundleComponentsBecomeOneApplicationProductAndDoNotMultiplyPlatformQuantity(){
        refund();long bundle=f.repo.insert("INSERT INTO oms_order_bundle(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,revision,snapshot) VALUES ('VERIFY',?,'987654321012345679','KIT','Kit',1,2,1,'{}')",f.order);
        f.db.update("UPDATE oms_order_line SET bundle_id=? WHERE order_id=?",bundle,f.order);f.db.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,bundle_id) VALUES ('VERIFY',?,'987654321012345679','B','component',3,0,?)",f.order,bundle);
        Map<String,Object> row=rows(Collections.emptyMap()).get(0);List<?> products=(List<?>)row.get("application_products");assertEquals(1,products.size());assertEquals("KIT",((Map<?,?>)products.get(0)).get("sku_sn"));assertEquals(1,number(row,"platform_quantity"));
    }
    @Test void databaseQueryCountDoesNotGrowWithPageSize(){
        refund();OrderRepository counted=spy(f.repo);f.refunds.repository=counted;rows(Collections.emptyMap());long single=mockingDetails(counted).getInvocations().stream().filter(call->call.getMethod().getName().equals("db")).count();
        for(int i=0;i<24;i++)refund();clearInvocations(counted);assertEquals(25,rows(Collections.singletonMap("pageSize","50")).size());long many=mockingDetails(counted).getInvocations().stream().filter(call->call.getMethod().getName().equals("db")).count();assertEquals(single,many);assertTrue(many<=6,"page enrichment must stay batched");
    }

    @Test void refundOnlyReturnScenarioIncludesApprovedIntentBeforeAnyDocumentAndCompletedReceipt(){
        long need=refund(),gift=refund(),done=refund(),plain=refund();
        f.db.update("UPDATE oms_refund SET review_state='APPROVED',goods_action='RETURN',goods_state='WAIT_ACTION',state='PROCESSING' WHERE id=?",need);
        f.db.update("UPDATE oms_refund SET review_state='APPROVED',goods_action='KEEP',goods_state='DONE',state='COMPLETED' WHERE id IN (?,?)",gift,done);
        plan("VERIFY",gift,true,1,0,0);plan("VERIFY",done,false,1,1,1);
        Map<String,String> q=new HashMap<>();q.put("scenario","REFUND_RETURN");q.put("pageSize","1");
        assertEquals(2,number(f.refunds.businessList("VERIFY",q),"total"));assertEquals(done,number(rows(q).get(0),"id"));q.put("pageNum","2");assertEquals(need,number(rows(q).get(0),"id"));
        assertEquals(gift,number(rows(Collections.singletonMap("scenario","REFUND_GIFT_ONLY")).get(0),"id"));
        assertEquals(0,number(f.refunds.businessList("OTHER",q),"total"));
        q.put("pageNum","1");q.put("task","GOODS");assertEquals(need,number(rows(q).get(0),"id"));
        assertEquals(1,rows(Collections.singletonMap("scenario","MISSING_DOCUMENT")).size());
        f.db.update("UPDATE oms_refund SET funds_state='SUCCESS' WHERE id=?",need);assertEquals(need,number(rows(Collections.singletonMap("scenario","PAID_PENDING")).get(0),"id"));
    }
    @Test void canceledUnreceivedPlansAndOtherRefundPlansCannotCreateReturnScenario(){
        long closed=refund(),waived=refund(),other=refund();long first=plan("VERIFY",closed,false,1,0,0),second=plan("VERIFY",waived,false,1,0,0);plan("OTHER",closed,false,9,0,0);plan("VERIFY",other,false,1,0,0);
        f.db.update("UPDATE oms_refund_return_plan SET state='CLOSED' WHERE id=?",first);f.db.update("UPDATE oms_refund_return_plan SET state='CLOSED',waived_quantity=1 WHERE id=?",second);
        List<Map<String,Object>> matches=rows(Collections.singletonMap("scenario","REFUND_RETURN"));assertEquals(1,matches.size());assertEquals(other,number(matches.get(0),"id"));
        assertEquals(1,number(object(rows(Collections.singletonMap("number",text(f.refunds.business("VERIFY",waived,false),"refund_sn"))).get(0),"purchase_progress"),"waived_quantity"));
    }
    @Test void missingDocumentAndPostingFiltersUsePhysicalFactsAndAllowCombinedAction(){
        long first=refund(),second=refund();f.db.update("UPDATE oms_refund SET review_state='APPROVED',goods_action='RETURN',goods_state='WAIT_RETURN',state='PROCESSING' WHERE id IN (?,?)",first,second);
        long p=plan("VERIFY",first,false,2,1,0);plan("VERIFY",second,true,1,0,0);f.db.update("UPDATE oms_refund_return_plan SET result_json=? WHERE id=?",encode(m("documents",Arrays.asList(m("id",1,"return_sn","THK_FIXTURE")))),p);
        assertEquals(second,number(rows(Collections.singletonMap("scenario","MISSING_DOCUMENT")).get(0),"id"));assertEquals(first,number(rows(Collections.singletonMap("scenario","UNPOSTED")).get(0),"id"));
        Map<String,String> q=new HashMap<>();q.put("scenario","REFUND_RETURN");q.put("goodsAction","RETURN");assertEquals(2,rows(q).size());
        assertThrows(IllegalArgumentException.class,()->rows(Collections.singletonMap("scenario","SQL")));assertThrows(IllegalArgumentException.class,()->rows(Collections.singletonMap("goodsAction","SQL")));
    }
    @Test void detailShowsOriginalCommercialLineAndSeparatesRefundScopedPhysicalTotals(){
        long id=refund();Map<String,Object> doc=m("source_modified","2026-10-21 10:00:00","buyer_freight","10.00","lines",Arrays.asList(m("platform_oid","987654321012345679","sales_amount","100.00","discount_total","15.00","goods_payment","85.00"),m("platform_oid","another","goods_payment","900.00")));
        f.db.update("UPDATE oms_order SET document_info=? WHERE id=?",encode(doc),f.order);plan("VERIFY",id,false,2,1,1);plan("VERIFY",id,true,1,0,0);
        Map<String,Object> d=f.workspace.detail("VERIFY",id);assertEquals("85.00",object(object(d,"sales_context"),"line").get("goods_payment"));assertEquals(2,number(object(d,"purchase_progress"),"return_quantity"));assertEquals(1,number(object(d,"gift_progress"),"return_quantity"));assertThrows(IllegalArgumentException.class,()->f.workspace.detail("OTHER",id));
    }
    @Test @SuppressWarnings("unchecked") void detailSeparatesLocalApprovalPlatformOperationAndConfirmedFunds(){
        long id=f.convert(f.ingest(f.source(1,"2.00","WAIT_SELLER_AGREE")));
        f.workspace.review("VERIFY",id,f.review(id,"DIFFERENCE","KEEP"),"test");
        Map<String,Object> detail=f.workspace.detail("VERIFY",id),interaction=object(detail,"platform_interaction");
        assertEquals(false,interaction.get("execution_supported"));assertEquals("SOURCE_SYNC",interaction.get("mode"));
        assertTrue(((List<?>)interaction.get("operations")).isEmpty());assertEquals("NONE",detail.get("funds_state"));
        Map<String,Object> observed=((List<Map<String,Object>>)interaction.get("observations")).get(0);
        assertEquals("WAIT_SELLER_AGREE",observed.get("platform_status"));assertFalse(observed.containsKey("payload"));
        assertEquals(false,object(detail,"capability").get("platform_agree"));
        f.db.update("INSERT INTO oms_refund_operation(company_code,refund_id,request_id,action,state,source_version,platform_version,fee,reason,actor) VALUES ('VERIFY',?,'PLATFORM_PROGRESS_TEST','AGREE','SUCCESS',1,'1',2,'isolated record fixture','test')",id);
        f.db.update("INSERT INTO oms_refund_operation(company_code,refund_id,request_id,action,state,source_version,platform_version,fee,reason,actor) VALUES ('OTHER',?,'FOREIGN_PLATFORM_PROGRESS','AGREE','SUCCESS',1,'1',999,'foreign fixture','test')",id);
        detail=f.workspace.detail("VERIFY",id);interaction=object(detail,"platform_interaction");
        assertEquals(1,((List<?>)interaction.get("operations")).size());assertEquals("NONE",detail.get("funds_state"));
        assertEquals("WAIT_SELLER_AGREE",object(detail,"platform").get("platform_status"));
        assertEquals("APPROVED",detail.get("review_state"));assertThrows(IllegalArgumentException.class,()->f.workspace.detail("OTHER",id));
    }

}
