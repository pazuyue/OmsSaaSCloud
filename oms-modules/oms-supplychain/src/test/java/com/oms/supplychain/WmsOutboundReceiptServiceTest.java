package com.oms.supplychain;

import com.oms.supplychain.service.wms.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

class WmsOutboundReceiptServiceTest {
    WmsOutboundServiceTest fixture=new WmsOutboundServiceTest();WmsOutboundReceiptService service;String key;
    @BeforeAll static void start(){WmsOutboundServiceTest.setup();}
    @AfterAll static void stop(){WmsOutboundServiceTest.close();}
    @BeforeEach void seed() throws Exception {fixture.seed();service=new WmsOutboundReceiptService();ReflectionTestUtils.setField(service,"db",fixture.db);ReflectionTestUtils.setField(service,"connections",fixture.connections);ReflectionTestUtils.setField(service,"logs",fixture.context.getBean(WmsInteractionLog.class));ReflectionTestUtils.setField(service,"protocols",Arrays.asList(new QimenProtocol(),new JdHufuProtocol()));}
    @AfterEach void close(){fixture.stop();}
    void warehouse(String provider){fixture.setupWarehouse(provider);fixture.service.submit("QM",fixture.payload(),false);key=fixture.jdbc.queryForObject("SELECT callback_key FROM wms_connection WHERE id=?",String.class,fixture.connection);}
    Map<String,String> params(String body){Map<String,String> p=new TreeMap<>();p.put("method",fixture.provider.equals("QIMEN")?"taobao.qimen.deliveryorder.confirm":"jingdong.hufu.deliveryorder.confirm");p.put("app_key","APP");p.put("customerId","CUSTOMER");p.put("v","2.0");p.put("sign_method","md5");p.put("timestamp",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));p.put("sign",QimenProtocol.sign(p,body,"fixture-secret"));return p;}
    String body(String message,long actual,boolean complete,Object...packages){List<Map<String,Object>> packs=new ArrayList<>();for(int i=0;i<packages.length;i+=2)packs.add(map("packageCode",packages[i],"expressCode","TEST-"+packages[i],"logisticsCode","SF","items",Arrays.asList(map("itemCode","A<&","quantity",packages[i+1]))));Map<String,Object> head=map("deliveryOrderCode","FH_TEST","deliveryOrderId","EXT-1","warehouseCode","EXT-W","ownerCode","EXT-O","status",complete?"DELIVERED":"PARTDELIVERED","confirmType",complete?"0":"1","outBizCode",message,"totalOrderLines",actual>0?1:0,"orderConfirmTime","2026-10-12 12:00:00");List<Map<String,Object>> lines=actual>0?Arrays.asList(map("orderLineNo","1","itemCode","A<&","ownerCode","EXT-O","batchCode","B001","inventoryType","ZP","actualQty",actual)):Collections.emptyList();if(fixture.provider.equals("JD_HUFU"))return fixture.db.encode(map("deliveryOrder",head,"orderLines",lines,"packages",packs));StringBuilder xml=new StringBuilder("<request><deliveryOrder>");head.forEach((k,v)->xml.append(QimenProtocol.tag(k,v)));xml.append("</deliveryOrder><orderLines>");for(Map<String,Object> line:lines){xml.append("<orderLine>");line.forEach((k,v)->xml.append(QimenProtocol.tag(k,v)));xml.append("</orderLine>");}xml.append("</orderLines><packages>");for(Map<String,Object> pack:packs){xml.append("<package>");for(String field:Arrays.asList("packageCode","expressCode","logisticsCode"))xml.append(QimenProtocol.tag(field,pack.get(field)));xml.append("<items><item>").append(QimenProtocol.tag("itemCode","A<&")).append(QimenProtocol.tag("quantity",((List<Map<String,Object>>)pack.get("items")).get(0).get("quantity"))).append("</item></items></package>");}return xml.append("</packages></request>").toString();}
    String callback(String body){return service.callback("QM",key,params(body),body);}
    long count(String table){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    @Test void signedQimenPartialCumulativeAndNewMessageDuplicateCountParcelsOnce(){warehouse("QIMEN");String first=body("MSG1",1,false,"P1",1);assertTrue(callback(first).contains("success"));assertTrue(callback(first).contains("success"));assertTrue(callback(body("MSG2",1,false,"P1",1)).contains("success"));assertEquals(2,count("wms_outbound_receipt_event"));assertEquals(1,count("wms_outbound_receipt_package"));assertTrue(callback(body("MSG3",3,true,"P1",1,"P2",2)).contains("success"));assertEquals(2,count("wms_outbound_receipt_package"));assertEquals(3,fixture.jdbc.queryForObject("SELECT SUM(quantity) FROM wms_outbound_receipt_event",Long.class));List<Map<String,Object>> events=service.pending("QM","FH_TEST",false);assertEquals(3,events.size());assertEquals(2,id(((Map<?,?>)events.get(2).get("payload")).get("quantity")));assertEquals(0,count("wms_receipt_line"));}
    @Test void hufuJsonHasSameVerifiedDeliveryContract(){warehouse("JD_HUFU");String body=body("JD1",2,false,"JDP1",2);assertTrue(callback(body).contains("success"));assertTrue(callback(body).contains("success"));assertEquals(1,count("wms_outbound_receipt_event"));assertEquals(2,fixture.jdbc.queryForObject("SELECT SUM(quantity) FROM wms_outbound_receipt_event",Long.class));}
    @Test void badSignatureOwnerBatchAndOverDeliveryNeverPersistStockInstructions(){warehouse("QIMEN");String body=body("BAD1",1,false,"P1",1);Map<String,String> p=params(body);p.put("sign","bad");assertTrue(service.callback("QM",key,p,body).contains("failure"));assertTrue(callback(body.replace("EXT-O","OTHER")).contains("failure"));assertTrue(callback(body.replace("B001","OTHER")).contains("failure"));assertTrue(callback(body("OVER",4,false,"P4",4)).contains("failure"));assertEquals(0,count("wms_outbound_receipt_event"));assertEquals(0,count("wms_outbound_receipt_package"));}
    @Test void changedMessageOrPreviouslySeenPackageIsRejected(){warehouse("QIMEN");assertTrue(callback(body("SAME",1,false,"P1",1)).contains("success"));assertTrue(callback(body("SAME",2,false,"P2",2)).contains("failure"));assertTrue(callback(body("OTHER",2,false,"P1",2)).contains("failure"));assertEquals(1,count("wms_outbound_receipt_event"));}
    @Test void parcelTotalsAndMissingMessagesCannotAdvanceDelivery(){warehouse("QIMEN");assertTrue(callback(body("MISMATCH",2,false,"P1",1)).contains("failure"));assertTrue(callback(body("",1,false,"P1",1)).contains("failure"));assertTrue(callback(body("EMPTY",0,true)).contains("failure"));assertEquals(0,count("wms_outbound_receipt_event"));}
    @Test void finalNoticeAfterActualPackageNeedsNoSecondConsumption(){warehouse("QIMEN");callback(body("FIRST",1,false,"P1",1));assertTrue(callback(body("FINAL",0,true)).contains("success"));assertEquals(1,fixture.jdbc.queryForObject("SELECT SUM(quantity) FROM wms_outbound_receipt_event",Long.class));service.posted("QM",service.pending("QM","FH_TEST",false).stream().mapToLong(e->id(e.get("id"))).min().getAsLong(),true,"done");assertEquals(1,service.pending("QM","FH_TEST",false).size());}
    @Test void decimalEncodedWholeQuantityIsAcceptedButFractionsAreRejected(){warehouse("QIMEN");String whole=body("DECIMAL",1,false,"P1",1).replace("<actualQty>1</actualQty>","<actualQty>1.0</actualQty>").replace("<quantity>1</quantity>","<quantity>1.0</quantity>");assertTrue(callback(whole).contains("success"));assertTrue(callback(body("FRACTION",1,false,"P2",1).replace("<actualQty>1</actualQty>","<actualQty>0.5</actualQty>")).contains("failure"));assertEquals(1,count("wms_outbound_receipt_event"));}
    @Test void erpShortMethodAndOptionalHeadOwnerUseBoundIdentity(){warehouse("QIMEN");String body=body("SHORT",1,false,"P1",1).replace("<deliveryOrderId>EXT-1</deliveryOrderId>","");int owner=body.indexOf("<ownerCode>EXT-O</ownerCode>");body=body.substring(0,owner)+body.substring(owner+"<ownerCode>EXT-O</ownerCode>".length());Map<String,String> p=params(body);p.put("method","deliveryorder.confirm");p.put("sign",QimenProtocol.sign(p,body,"fixture-secret"));assertTrue(service.callback("QM",key,p,body).contains("success"));assertEquals(1,count("wms_outbound_receipt_event"));}
    @Test void parallelRepeatClaimsOneCallbackEvent() throws Exception {warehouse("QIMEN");String body=body("PARALLEL",1,false,"P1",1);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{List<Future<String>> jobs=new ArrayList<>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{start.await();return callback(body);}));start.countDown();for(Future<String> job:jobs)assertTrue(job.get(15,TimeUnit.SECONDS).contains("success"));}finally{pool.shutdownNow();}assertEquals(1,count("wms_outbound_receipt_event"));assertEquals(1,count("wms_outbound_receipt_package"));}
    String conflict(){String b=body("CONFLICT",2,true,"P2",2).replace("TEST-P2","TEST-P1");assertTrue(callback(b).contains("failure"));return b;}
    Map<String,Object> decision(String code){return map("decision",code,"reason","已核对实际出库事实与共用运单","actor","reviewer","control_version",0,"related_shipment_ids",Arrays.asList(1));}
    @Test void rejectedWaybillIsDurableAndConfirmationReusesOriginalReceiptWithoutWarehouseResend(){
        warehouse("QIMEN");assertTrue(callback(body("FIRST",1,false,"P1",1)).contains("success"));String b=conflict();assertTrue(callback(b).contains("failure"));
        assertEquals(1,count("wms_outbound_receipt_review"));assertEquals(1,count("wms_outbound_receipt_event"));
        Map<String,Object> review=service.reviews("QM",1).get(0);assertEquals("WAIT_REVIEW",review.get("state"));assertEquals(true,review.get("can_confirm_shared"));
        assertEquals(1,((List<?>)review.get("existing_packages")).size());assertThrows(IllegalArgumentException.class,()->service.review("OTHER",1,id(review.get("id")),decision("CONFIRM_SHARED")));
        Map<String,Object> result=service.review("QM",1,id(review.get("id")),decision("CONFIRM_SHARED"));assertEquals("CONFIRMED",result.get("state"));
        assertEquals(2,count("wms_outbound_receipt_event"));assertEquals(2,count("wms_outbound_receipt_package"));assertEquals(3,fixture.jdbc.queryForObject("SELECT SUM(quantity) FROM wms_outbound_receipt_event",Long.class));
        service.review("QM",1,id(review.get("id")),decision("CONFIRM_SHARED"));assertTrue(callback(b).contains("success"));assertEquals(2,count("wms_outbound_receipt_event"));
        Map<String,Object> event=service.pending("QM","FH_TEST",false).get(1);assertEquals(id(review.get("id")),id(((Map<?,?>)((List<?>)((Map<?,?>)event.get("payload")).get("packages")).get(0)).get("shared_review_id")));
        assertEquals("reviewer",service.reviews("QM",1).get(0).get("operator_name"));
    }
    @Test void scheduledReceiptBatchKeepsRejectedCallbacksOutOfPostingEvents(){
        warehouse("QIMEN");callback(body("FIRST",1,false,"P1",1));conflict();Map<String,Object> batch=service.scanPending("QM");
        assertEquals(1,id(batch.get("review_pending")));assertEquals(1,((List<?>)batch.get("events")).size());assertEquals(1,((List<?>)batch.get("reviews")).size());
        service.scanPending("QM");assertEquals("WAIT_REVIEW",service.reviews("QM",1).get(0).get("state"));assertEquals(1,count("wms_outbound_receipt_event"));assertEquals(0,id(service.scanPending("OTHER").get("review_pending")));
    }
    @Test void pendingSummaryIsTenantScopedAndExcludesDecidedReviews(){
        warehouse("QIMEN");callback(body("FIRST",1,false,"P1",1));conflict();
        List<Map<String,Object>> summaries=service.reviewSummaries("QM",Arrays.asList(1L,999L));assertEquals(1,summaries.size());assertEquals(1,id(summaries.get(0).get("pending_count")));
        assertTrue(service.reviewSummaries("OTHER",Arrays.asList(1L)).isEmpty());assertTrue(service.reviewSummaries("QM",Arrays.asList(999L)).isEmpty());assertTrue(service.reviewSummaries("QM",Collections.emptyList()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.reviewSummaries("QM",Arrays.asList(0L)));assertThrows(IllegalArgumentException.class,()->service.reviewSummaries("QM",Collections.nCopies(101,1L)));
        service.review("QM",1,id(service.reviews("QM",1).get(0).get("id")),decision("REJECT"));assertTrue(service.reviewSummaries("QM",Arrays.asList(1L)).isEmpty());
    }
    @Test void humanRejectionDoesNotPublishStockEventAndCannotLaterBeChangedToAcceptance(){
        warehouse("QIMEN");callback(body("FIRST",1,false,"P1",1));conflict();long review=id(service.reviews("QM",1).get(0).get("id"));
        assertEquals("REJECTED",service.review("QM",1,review,decision("REJECT")).get("state"));
        assertEquals(1,count("wms_outbound_receipt_event"));assertThrows(IllegalArgumentException.class,()->service.review("QM",1,review,decision("CONFIRM_SHARED")));
    }
    @Test void changedAcceptedPackageCannotBeConfirmedAsSharedWaybill(){
        warehouse("QIMEN");callback(body("FIRST",1,false,"P1",1));assertTrue(callback(body("CHANGED",2,true,"P1",2)).contains("failure"));
        Map<String,Object> review=service.reviews("QM",1).get(0);assertEquals("PACKAGE_CHANGED",review.get("conflict_code"));assertEquals(false,review.get("can_confirm_shared"));
        assertThrows(IllegalArgumentException.class,()->service.review("QM",1,id(review.get("id")),decision("CONFIRM_SHARED")));assertEquals(1,count("wms_outbound_receipt_event"));
    }
    @Test void sharingStillChecksOrderMembershipSnapshotAndReason(){
        warehouse("QIMEN");callback(body("FIRST",1,false,"P1",1));conflict();long review=id(service.reviews("QM",1).get(0).get("id"));
        Map<String,Object> bad=decision("CONFIRM_SHARED");bad.put("related_shipment_ids",Arrays.asList(999));final Map<String,Object> other=bad;assertThrows(IllegalArgumentException.class,()->service.review("QM",1,review,other));
        bad=decision("CONFIRM_SHARED");bad.put("reason","");final Map<String,Object> empty=bad;assertThrows(IllegalArgumentException.class,()->service.review("QM",1,review,empty));
        fixture.jdbc.update("UPDATE wms_outbound_receipt_review SET receipt_hash='corrupted' WHERE id=?",review);assertThrows(IllegalArgumentException.class,()->service.review("QM",1,review,decision("CONFIRM_SHARED")));assertEquals(1,count("wms_outbound_receipt_event"));
    }

}
