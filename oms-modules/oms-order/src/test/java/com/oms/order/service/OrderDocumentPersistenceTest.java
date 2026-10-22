package com.oms.order.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderDocumentPersistenceTest {
    OrderRepositoryTest fixture;
    @BeforeAll static void start(){OrderRepositoryTest.start();}
    @AfterAll static void stop(){OrderRepositoryTest.stop();}
    @BeforeEach void reset(){fixture=new OrderRepositoryTest();fixture.clear();}
    @Test void conversionCopiesCommercialSnapshotOnceAndListDoesNotExposeNotes(){
        OrderRepository repo=OrderRepositoryTest.repository;ObjectNode t=OrderDocumentInfoTest.trade();String payload=OrderDocumentInfoTest.payload(t);
        repo.page("VERIFY",1,OrderRepositoryTest.LEASE,fixture.task("MANUAL"),Arrays.asList(fixture.row("2026-10-21 13:00:00",payload)),50);long id=fixture.n("SELECT id FROM oms_platform_order");repo.snapshot("VERIFY",id,OrderRepositoryTest.LEASE);
        TmallOrderParser.Trade parsed=new TmallOrderParser().parse(payload);Map<String,String> matched=new LinkedHashMap<>();List<Map<String,Object>> stock=new ArrayList<>();for(TmallOrderParser.Line l:parsed.lines){matched.put(l.oid,l.outerSku);Map<String,Object> s=new LinkedHashMap<>();s.put("platform_oid",l.oid);s.put("sku_sn",l.outerSku);s.put("reserved_quantity",0);s.put("reason","shortage");stock.add(s);}
        repo.parsed("VERIFY",id,OrderRepositoryTest.LEASE,parsed,matched,Collections.emptyMap());repo.complete("VERIFY",id,OrderRepositoryTest.LEASE,repo.config("VERIFY",1),stock);
        String before=OrderRepositoryTest.db.queryForObject("SELECT document_info FROM oms_order",String.class);assertEquals("40.00",OrderDocumentInfo.stored(before).get("discount_total"));assertEquals("10.00",OrderDocumentInfo.stored(before).get("buyer_freight"));assertEquals(2,OrderDocumentInfoTest.lines(OrderDocumentInfo.stored(before)).size());
        repo.complete("VERIFY",id,OrderRepositoryTest.LEASE,repo.config("VERIFY",1),stock);assertEquals(before,OrderRepositoryTest.db.queryForObject("SELECT document_info FROM oms_order",String.class));
        Map<String,Object> view=new LinkedHashMap<>();view.put("document_info",before);OrderDocumentInfo.attach(view);assertTrue(view.get("document") instanceof Map);assertFalse(view.containsKey("document_info"));
        Map<?,?> list=(Map<?,?>)fixture.businessService().list("VERIFY",null,"","","","","",0,1,20).getRows().get(0);assertFalse(list.containsKey("document_info"));assertFalse(list.containsKey("document"));
    }

    @Test void internalNotesAreScopedAndDoNotChangeFulfillmentVersion(){
        long order=fixture.businessOrder();OrderRepository repo=OrderRepositoryTest.repository;
        long version=fixture.n("SELECT control_version FROM oms_order");OrderDocumentInfo.addNote(repo,"VERIFY",order,"单独记录处理说明","tester");
        assertThrows(IllegalArgumentException.class,()->OrderDocumentInfo.addNote(repo,"OTHER",order,"不能跨公司备注","tester"));
        assertThrows(IllegalArgumentException.class,()->OrderDocumentInfo.addNote(repo,"VERIFY",order,"  ","tester"));
        Map<String,Object> view=OrderRepositoryTest.db.queryForMap("SELECT * FROM oms_order WHERE id=?",order);OrderDocumentInfo.related(repo,"VERIFY",view);
        assertEquals(1,((List<?>)view.get("internal_notes")).size());assertEquals(version,fixture.n("SELECT control_version FROM oms_order"));
        assertEquals(0,new java.math.BigDecimal(((Map<?,?>)view.get("refund_amounts")).get("succeeded").toString()).signum());
    }
}
