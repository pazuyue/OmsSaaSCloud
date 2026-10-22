package com.oms.messaging;

import com.ruoyi.common.core.tracing.Tracing;
import org.apache.skywalking.apm.toolkit.trace.Tracer;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageTracingTest {
    MessageOutbox out;
    @BeforeEach void setup() {
        String url=System.getenv("MESSAGE_TEST_URL");
        Assumptions.assumeTrue(url!=null && url.contains("/message_delivery_test?"));
        DriverManagerDataSource source=new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));
        out=new MessageOutbox();out.db=new JdbcTemplate(source);out.transactionManager=new DataSourceTransactionManager(source);out.service="channel";
        out.db.update("DELETE FROM oms_message_attempt");out.db.update("DELETE FROM oms_message_outbox");
    }
    @AfterEach void cleanup(){MDC.clear();}

    String enqueue(String company){return out.enqueue(company,"PRODUCT_PULL",81,1,"TRACING81","81:1",0);}

    @Test void customListenerCarriesPublisherContextAndClosesItAfterConsumption() {
        Map<String,Object> headers=new HashMap<>();
        Tracer.createEntrySpan("tracing.test.publisher",null);
        String publisher;
        try {
            publisher=Tracing.traceId();
            MessageTrace.send("PRODUCT_PULL","localhost:5672",headers,()->{});
        }finally{Tracer.stopSpan();}
        assertNull(Tracing.traceId());
        Map<String,Object> row=new HashMap<>();row.put("message_type","PRODUCT_PULL");
        try(MessageTrace trace=MessageTrace.receive("QM",row,headers)) {
            assertEquals(publisher,Tracing.traceId());
            if(Boolean.getBoolean("skywalking.test.agent"))assertNotNull(Tracing.traceId());
        }
        assertNull(Tracing.traceId());
    }

    @Test void sourceAndAttemptIdentifiersAreRecordedAndTenantScopedSearchWorks() {
        Tracer.createEntrySpan("tracing.test.source",null);
        String source;
        String event;
        try {source=Tracing.traceId();event=enqueue("QM");}
        finally {Tracer.stopSpan();}
        if(Boolean.getBoolean("skywalking.test.agent")) assertNotNull(source);
        assertEquals(source,out.row("QM",event).get("source_trace_id"));
        Map<String,Object> row=out.row("QM",event);
        try(MessageTrace trace=MessageTrace.start("process","QM",row)) {
            out.attempt("QM",event,"PROCESS","SUCCESS","MQ","done",1,"tester");
        }
        String attempt=(String)out.db.queryForObject("SELECT sw_trace_id FROM oms_message_attempt WHERE event_id=?",String.class,event);
        if(Boolean.getBoolean("skywalking.test.agent")){assertNotNull(attempt);assertNotEquals(source,attempt);}
        // Stable synthetic identifiers exercise search even when the agent is disabled.
        out.db.update("UPDATE oms_message_outbox SET source_trace_id='source-test' WHERE event_id=?",event);
        out.db.update("UPDATE oms_message_attempt SET sw_trace_id='attempt-test' WHERE event_id=?",event);
        for(String key:Arrays.asList("source-test","attempt-test")) {
            assertEquals(1,((Number)out.list("QM","","","",key,1,20).get("total")).intValue());
            assertEquals(0,((Number)out.list("OTHER","","","",key,1,20).get("total")).intValue());
        }
        Map<String,Object> detail=out.detail("QM",event);
        assertEquals("attempt-test",((List<Map<String,Object>>)detail.get("attempts")).get(0).get("sw_trace_id"));
        assertTrue(((String)row.get("trace_id")).startsWith("MT"));
        assertFalse(out.envelope(row).containsKey("source_trace_id"));
    }

    @Test void reusedWorkerRestoresLoggingContextAfterFailureAndDoesNotMixTenants() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        Map<String,Object> row=out.row("QM",enqueue("QM"));
        try {
            pool.submit(()->{
                MDC.put("eventId","outer");
                try(MessageTrace trace=MessageTrace.start("process","QM",row)) {
                    assertEquals("QM",MDC.get("companyCode"));
                    assertEquals(row.get("event_id"),MDC.get("eventId"));
                    trace.error(new IllegalStateException("secret payload must not be collected"));
                    throw new IllegalStateException("expected");
                }catch(IllegalStateException expected) {assertEquals("outer",MDC.get("eventId"));assertNull(MDC.get("companyCode"));}
                finally {MDC.clear();}
            }).get(10,TimeUnit.SECONDS);
            pool.submit(()->{
                assertNull(MDC.get("companyCode"));assertNull(MDC.get("eventId"));
                assertNull(Tracing.traceId());
                try(MessageTrace trace=MessageTrace.start("process","OTHER",row)){assertEquals("OTHER",MDC.get("companyCode"));}
                assertNull(Tracing.traceId());assertNull(MDC.get("companyCode"));
            }).get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
    }
}
