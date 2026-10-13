package com.oms.order.service;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.order.service.OrderValues.*;

class OrderIssueServiceTest {
    OrderShipmentServiceTest fixture;OrderIssueService service;
    @BeforeAll static void setup(){OrderShipmentServiceTest.setup();}
    @BeforeEach void seed(){fixture=new OrderShipmentServiceTest();fixture.seed();fixture.db.update("DELETE FROM oms_order_issue_event");fixture.db.update("DELETE FROM oms_order_issue");service=new OrderIssueService(fixture.repository);}
    Map<String,Object> row(Object... pairs){return fixture.row(pairs);}
    Map<String,Object> head(){return fixture.db.query("SELECT * FROM oms_order WHERE id=?",ROW,fixture.id).get(0);}
    Map<String,Object> issue(){return row("domain","INVENTORY","category","QUOTA","reason_code","CHANNEL_QUOTA_EXCEEDED","scope","CHANNEL","stage","RESERVE","sku_sn","A","severity","WARNING","blocking",0,"message","渠道额度不足，公共补占已满足需求","evidence",row("basis","CURRENT_BALANCE","excess_quantity",10));}
    Map<String,Object> facts(List<Map<String,Object>> issues){return row("channel_id",1,"platform_tid","12345","lines",Arrays.asList(row("platform_oid","123450","sku_sn","A","demand_quantity",10,"reserved_quantity",10)),"sku_facts",Arrays.asList(row("sku_sn","A","evidence",row("locked_reserved",0,"public_reserved",10),"issues",issues)));}
    Map<String,Object> saved(){return fixture.db.query("SELECT * FROM oms_order_issue",ROW).get(0);}
    @Test void syncIsIdempotentAndAcknowledgementDoesNotLiftBlocking(){
        Map<String,Object> problem=issue();problem.put("blocking",1);problem.put("severity","ERROR");Map<String,Object> input=facts(Arrays.asList(problem));service.sync("VERIFY",head(),input);service.sync("VERIFY",head(),input);assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM oms_order_issue_event",Integer.class));
        long id=number(saved(),"id");service.acknowledge("VERIFY",fixture.id,id,"已知悉，等待补货","operator");service.acknowledge("VERIFY",fixture.id,id,"重复点击","operator");service.sync("VERIFY",head(),input);assertEquals("ACKNOWLEDGED",saved().get("state"));assertEquals(1,number(saved(),"blocking"));assertEquals(2,fixture.db.queryForObject("SELECT COUNT(*) FROM oms_order_issue_event",Integer.class));assertThrows(IllegalArgumentException.class,()->service.acknowledge("OTHER",fixture.id,id,"test","operator"));
    }
    @Test void resolveAndReopenRetainOriginalEvidenceAndOnlyTouchTheSyncedDomain(){
        service.sync("VERIFY",head(),facts(Arrays.asList(issue())));long id=number(saved(),"id");fixture.db.update("INSERT INTO oms_order_issue(company_code,order_id,domain,issue_key,category,reason_code,scope,stage,severity,message,evidence_json,evidence_hash) VALUES ('VERIFY',?,'FULFILLMENT','other','ADDRESS','ADDRESS_INVALID','ORDER','DISPATCH','ERROR','address','{}','hash')",fixture.id);
        service.sync("VERIFY",head(),facts(Collections.emptyList()));assertEquals("RESOLVED",fixture.db.queryForObject("SELECT state FROM oms_order_issue WHERE id=?",String.class,id));assertEquals("OPEN",fixture.db.queryForObject("SELECT state FROM oms_order_issue WHERE domain='FULFILLMENT'",String.class));assertNull(((List<Map<String,Object>>)fixture.business.detail("VERIFY",fixture.id).get("lines")).get(0).get("issue_evidence"));service.sync("VERIFY",head(),facts(Arrays.asList(issue())));assertEquals("OPEN",fixture.db.queryForObject("SELECT state FROM oms_order_issue WHERE id=?",String.class,id));assertEquals(3,fixture.db.queryForObject("SELECT COUNT(*) FROM oms_order_issue_event",Integer.class));assertEquals(10,fixture.db.queryForObject("SELECT reserved_quantity FROM oms_order_line",Integer.class));
    }
    @Test void incompleteOrWrongRemoteFactsCannotClearAnExistingIssue(){
        service.sync("VERIFY",head(),facts(Arrays.asList(issue())));Map<String,Object> wrong=facts(Collections.emptyList());wrong.put("lines",Collections.emptyList());assertThrows(IllegalArgumentException.class,()->service.sync("VERIFY",head(),wrong));Map<String,Object> finalWrong=facts(Collections.emptyList());finalWrong.put("platform_tid","other");assertThrows(IllegalArgumentException.class,()->service.sync("VERIFY",head(),finalWrong));assertEquals("OPEN",saved().get("state"));assertThrows(IllegalArgumentException.class,()->service.report("OTHER",fixture.id));
    }
    @Test void listFiltersUseTheSameIssueForTypeScopeImpactAndState(){
        service.sync("VERIFY",head(),facts(Arrays.asList(issue())));assertEquals(1,fixture.business.list("VERIFY",null,"","","","","",0,1,20,"","","","QUOTA","CHANNEL","0","OPEN").getTotal());assertEquals(0,fixture.business.list("VERIFY",null,"","","","","",0,1,20,"","","","QUOTA","COMPANY","0","OPEN").getTotal());assertEquals(0,fixture.business.list("VERIFY",null,"","","","","",0,1,20,"","","","QUOTA","ORDER","0","OPEN").getTotal());assertEquals(0,fixture.business.list("OTHER",null,"","","","","",0,1,20,"","","","QUOTA","CHANNEL","0","OPEN").getTotal());service.acknowledge("VERIFY",fixture.id,number(saved(),"id"),"已核对公共补占","operator");assertEquals(1,fixture.business.list("VERIFY",null,"","","","","",0,1,20,"","","","QUOTA","CHANNEL","0","ACKNOWLEDGED").getTotal());
    }
}
