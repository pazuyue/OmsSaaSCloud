package com.ruoyi.system.messaging;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.system.messaging.QueueControlStore.*;

class QueueDeadLettersTest {
 QueueDeadLetters store;
 @BeforeEach void setup(){String url=System.getenv("QUEUE_CONTROL_TEST_URL");Assumptions.assumeTrue(url!=null&&url.contains("/queue_control_test?"));store=new QueueDeadLetters();store.db=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD")));store.db.update("DELETE FROM sys_mq_dead_review");store.db.update("DELETE FROM sys_mq_dead_letter");}
 Map<String,Object> report(){Map<String,Object> r=new HashMap<>();r.put("service","channel");r.put("business_type","PRODUCT_PULL");r.put("fingerprint",String.join("",Collections.nCopies(64,"a")));r.put("verified",true);r.put("company_code","QM");r.put("event_id","ME"+String.join("",Collections.nCopies(32,"b")));r.put("latest_event_id",r.get("event_id"));r.put("business_number","PRODUCTPULL1");r.put("death_reason","rejected");r.put("business_state","PENDING");return r;}
 @Test void durableIdentityIsIdempotentAndCompletionStopsReconciliation(){Map<String,Object> r=report();store.report(r);store.report(r);assertEquals(1L,store.list("PRODUCT_PULL",1,20).get("total"));assertEquals(1,store.checks("channel").size());r.put("business_state","SUCCESS");store.report(r);assertTrue(store.checks("channel").isEmpty());}
 @Test void reviewIsAppendOnlyAndNeverChangesBusinessState(){store.report(report());long id=store.db.queryForObject("SELECT id FROM sys_mq_dead_letter",Long.class);store.review(id,"已核对原业务","admin");store.review(id,"继续跟进业务配置","admin");assertEquals(2,store.reviews(id).size());assertEquals("PENDING",store.db.queryForObject("SELECT business_state FROM sys_mq_dead_letter",String.class));assertThrows(IllegalArgumentException.class,()->store.review(id," ","admin"));}
 @Test void unverifiedMessagesCannotClaimCompanyAndServiceCannotInventQueue(){Map<String,Object> r=report();r.put("verified",false);assertThrows(IllegalArgumentException.class,()->store.report(r));Map<String,Object> invalid=report();invalid.put("service","order");assertThrows(IllegalArgumentException.class,()->store.report(invalid));}
 @Test void sameFingerprintCannotBeReassignedToAnotherCompany(){Map<String,Object> r=report();store.report(r);r.put("company_code","OTHER");r.put("business_state","SUCCESS");store.report(r);assertEquals("QM",store.db.queryForObject("SELECT company_code FROM sys_mq_dead_letter",String.class));assertEquals("PENDING",store.db.queryForObject("SELECT business_state FROM sys_mq_dead_letter",String.class));}
}
