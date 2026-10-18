package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.order.service.ExchangeService.map;
class OrderPlatformInteractionRecorderTest {
    OrderPlatformShippingServiceTest fixture;OrderPlatformInteractionClient client;
    @BeforeAll static void start(){OrderVirtualOutboundServiceTest.start();}
    @BeforeEach void setup(){fixture=new OrderPlatformShippingServiceTest();fixture.seed();OrderPlatformInteractionRecorder recorder=new OrderPlatformInteractionRecorder();recorder.repository=fixture.f.fixture.repository;client=mock(OrderPlatformInteractionClient.class);recorder.client=client;when(client.start(anyString(),anyMap(),anyString())).thenReturn(R.ok(map("id",1,"request_id","PL_TEST")));when(client.finish(anyString(),anyMap(),anyString())).thenReturn(R.ok(true));ReflectionTestUtils.setField(fixture.gateway,"interactions",recorder);fixture.parcel(fixture.f.shipment,fixture.line,4,"CENTRAL_LOG");fixture.service.enqueue("VERIFY",fixture.f.shipment,"tester");}
    @Test void oneActualSimulatedSendCreatesOneCentralAttemptAndKeepsStockFacts(){String before=fixture.f.document().get("outbound_records").toString();fixture.service.process("VERIFY",fixture.task());fixture.service.process("VERIFY",fixture.task());assertEquals("SUCCESS",fixture.state());verify(client,times(1)).start(eq("VERIFY"),argThat(body->"SIMULATION.shipping.send".equals(body.get("method"))&&body.containsKey("refs")),anyString());verify(client,times(1)).finish(eq("VERIFY"),argThat(body->"SUCCESS".equals(body.get("result"))),anyString());assertEquals(before,fixture.f.document().get("outbound_records").toString());}
    @Test void unavailableDurableLoggingPreventsNewSubmission(){when(client.start(anyString(),anyMap(),anyString())).thenReturn(R.fail("log unavailable"));fixture.service.process("VERIFY",fixture.task());assertEquals("PENDING",fixture.state());assertEquals(0,fixture.count("oms_order_platform_shipping_sim_receipt"));verify(client,never()).finish(anyString(),anyMap(),anyString());}
    @Test void preInvocationFailureExhaustsBoundedRetriesAndCanBeRetriedFromOriginalTask(){when(client.start(anyString(),anyMap(),anyString())).thenReturn(R.fail("log unavailable"));for(int i=0;i<3;i++){fixture.service.process("VERIFY",fixture.task());fixture.due();}assertEquals("FAILED",fixture.state());assertEquals(0,fixture.count("oms_order_platform_shipping_sim_receipt"));when(client.start(anyString(),anyMap(),anyString())).thenReturn(R.ok(map("id",1,"request_id","PL_TEST")));fixture.service.action("VERIFY",fixture.f.shipment,fixture.task(),"RETRY","tester");fixture.service.process("VERIFY",fixture.task());assertEquals("SUCCESS",fixture.state());assertEquals(1,fixture.count("oms_order_platform_shipping_sim_receipt"));}
}
