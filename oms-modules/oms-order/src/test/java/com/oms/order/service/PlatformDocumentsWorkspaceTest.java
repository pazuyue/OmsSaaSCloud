package com.oms.order.service;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.oms.order.service.ExchangeService.map;
import static com.oms.order.service.OrderValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformDocumentsWorkspaceTest {
    ExchangeServiceTest f;
    PlatformDocumentsWorkspace workspace;
    PlatformExchangeService exchanges;
    RefundService refunds;
    final Set<String> all=new LinkedHashSet<>(Arrays.asList("ORDER","REFUND","EXCHANGE"));
    final String tid="987654321012345678",oid="987654321012345679";
    @BeforeAll static void start(){ExchangeServiceTest.start();}
    @BeforeEach void setup(){
        f=new ExchangeServiceTest();f.reset();
        for(String t:Arrays.asList("oms_platform_refund_snapshot","oms_platform_refund","oms_refund"))ExchangeServiceTest.db.update("DELETE FROM "+t);
        refunds=new RefundService();refunds.repository=f.repo;
        exchanges=new PlatformExchangeService(f.repo,f.service);
        workspace=new PlatformDocumentsWorkspace();workspace.repository=f.repo;workspace.catalog=f.catalog;workspace.refunds=refunds;workspace.exchanges=exchanges;
        workspace.orders=new OrderWorkspaceService();ReflectionTestUtils.setField(workspace.orders,"repository",f.repo);
        ExchangeServiceTest.db.update("INSERT INTO oms_platform_order_line(company_code,platform_order_id,platform_oid,sku_sn,quantity,payment) VALUES ('VERIFY',?,?,'A',5,25)",f.platform,oid);
        RefundServiceTest r=new RefundServiceTest();r.repo=f.repo;r.refunds=refunds;r.ingest(r.source(1,"2.00","WAIT_SELLER_AGREE"));
        exchanges.ingest("VERIFY",1,f.source(),"test");
    }
    Map<String,String> q(String... pairs){Map<String,String> q=new HashMap<>();for(int i=0;i<pairs.length;i+=2)q.put(pairs[i],pairs[i+1]);return q;}
    long total(String kind,Map<String,String> q){return number(workspace.list("VERIFY",all,kind,q),"total");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> groups(Map<String,Object> result){return (List<Map<String,Object>>)result.get("groups");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> group){return (List<Map<String,Object>>)group.get("rows");}
    @Test void sharedIdentifiersFilterAllThreeLocalSourcesExactly(){for(String kind:all){assertEquals(1,total(kind,q("platformTid",tid,"platformOid",oid,"channelId","1")));assertEquals(0,total(kind,q("platformTid",tid+"9")));assertEquals(0,total(kind,q("platformOid",oid+"9")));assertEquals(0,total(kind,q("channelId","2")));}}
    @Test void sourceModificationBoundsApplyToEveryType(){for(String kind:all){assertEquals(1,total(kind,q("modifiedFrom","2026-10-01 00:00:00","modifiedTo","2026-10-17 00:00:00")));assertEquals(0,total(kind,q("modifiedFrom","2026-10-17 00:00:00")));assertEquals(0,total(kind,q("modifiedTo","2026-09-30 00:00:00")));}}
    @Test void relatedDoesNotRequireConvertedBusinessOrExposePayload(){Map<String,Object> result=workspace.related("VERIFY",all,q("platformTid",tid));assertEquals(tid,result.get("platform_tid"));assertEquals(3,groups(result).size());for(Map<String,Object> group:groups(result))assertEquals(1,rows(group).size());assertEquals("SHIPPED",rows(groups(result).get(0)).get(0).get("business_state"));assertNull(rows(groups(result).get(1)).get(0).get("business_id"));assertFalse(result.toString().contains("payload"));assertFalse(result.toString().contains("buyer_phone"));verifyNoInteractions(f.bridge,f.warehouse);}
    @Test void companyShopAndTypePermissionsStayIsolated(){assertEquals(0,rows(groups(workspace.related("OTHER",all,q("platformTid",tid))).get(0)).size());assertEquals(0,rows(groups(workspace.related("VERIFY",all,q("platformTid",tid,"channelId","2"))).get(1)).size());Set<String> only=Collections.singleton("REFUND");assertEquals(1,groups(workspace.related("VERIFY",only,q("platformTid",tid))).size());assertThrows(IllegalArgumentException.class,()->workspace.list("VERIFY",only,"ORDER",q()));assertThrows(IllegalArgumentException.class,()->workspace.list("VERIFY",all,"UNTRUSTED",q()));}
    @Test void sourceWithoutOriginalSoRemainsVisibleAndRelated(){ExchangeServiceTest.db.update("UPDATE oms_platform_order SET business_order_id=NULL");assertNull(rows(groups(workspace.related("VERIFY",Collections.singleton("ORDER"),q("platformTid",tid))).get(0)).get(0).get("business_state"));ExchangeServiceTest.db.update("UPDATE oms_platform_refund SET platform_tid='999999',platform_oid='888888'");assertEquals(1,total("REFUND",q("platformTid","999999")));assertEquals(1,rows(groups(workspace.related("VERIFY",Collections.singleton("REFUND"),q("platformTid","999999"))).get(0)).size());}
    @Test void relatedIsBoundedAndIndicatesMoreWithoutCountingEveryRecord(){for(int i=0;i<51;i++)ExchangeServiceTest.db.update("INSERT INTO oms_platform_refund(company_code,channel_id,refund_id,platform_tid,platform_oid,seller_nick,jdp_modified,source_hash,payload) SELECT company_code,channel_id,?,platform_tid,platform_oid,seller_nick,jdp_modified,source_hash,payload FROM oms_platform_refund LIMIT 1","999"+i);Map<String,Object> group=groups(workspace.related("VERIFY",Collections.singleton("REFUND"),q("platformTid",tid))).get(0);assertEquals(50,rows(group).size());assertEquals(true,group.get("has_more"));}
    @Test void invalidAndUnboundedQueriesAreRejected(){for(Map<String,String> q:Arrays.asList(q("platformTid","1 OR 1=1"),q("platformOid","1.5"),q("pageNum","0"),q("pageSize","51"),q("pageNum","1001"),q("channelId","-1"),q("modifiedFrom","2026-10-17 00:00:00","modifiedTo","2026-10-01 00:00:00")))for(String kind:all)assertThrows(IllegalArgumentException.class,()->workspace.list("VERIFY",all,kind,q));assertThrows(IllegalArgumentException.class,()->workspace.related("VERIFY",all,q()));}
    @Test void optionsReturnSafeLocalShopFieldsOnly(){when(f.catalog.channels("VERIFY")).thenReturn(Arrays.asList(map("channel_id",1,"channel_type","TM","channel_name","shop","token","private"),map("channel_id",2,"channel_type","OTHER")));Map<String,Object> result=workspace.options("VERIFY",Collections.singleton("REFUND"));assertEquals(Collections.singleton("REFUND"),result.get("types"));assertFalse(result.toString().contains("private"));assertEquals(1,((List<?>)result.get("channels")).size());verifyNoInteractions(f.bridge,f.warehouse);}
}
