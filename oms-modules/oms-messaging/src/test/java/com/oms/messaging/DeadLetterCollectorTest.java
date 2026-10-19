package com.oms.messaging;

import org.junit.jupiter.api.*;
import com.rabbitmq.client.*;
import com.ruoyi.common.core.messaging.QueueKind;
import com.ruoyi.common.core.domain.R;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.oms.messaging.MessageOutbox.*;

class DeadLetterCollectorTest {
 MessageDeliveryTest base;DeadLetterCollector collector;
 @BeforeEach void setup(){base=new MessageDeliveryTest();base.setup();collector=new DeadLetterCollector();collector.outbox=base.out;collector.runtime=base.runtime;collector.tenants=base.runtime.tenants;collector.rabbit=base.runtime.rabbit;collector.control=mock(QueueControlClient.class);when(collector.tenants.companies()).thenReturn(Collections.singleton("QM"));}
 GetResponse response(byte[] body,boolean verified){Map<String,Object> headers=new HashMap<>();if(verified){Map<String,Object> death=new HashMap<>();death.put("queue",QueueKind.PRODUCT_PULL.queue);death.put("reason","rejected");death.put("count",1L);headers.put("x-death",Collections.singletonList(death));}return new GetResponse(new Envelope(1,false,"oms.dead.v1","PRODUCT_PULL"),new AMQP.BasicProperties.Builder().headers(headers).build(),body,0);}
 byte[] body(String event)throws Exception{return collector.json.writeValueAsBytes(base.out.envelope(base.out.row("QM",event)));}
 @Test void completedBusinessIsLinkedWithoutReplayingIt()throws Exception{String event=base.event();base.runtime.execute("QM",base.out.row("QM",event),"COMPENSATION");Map<String,Object> r=collector.inspect(QueueKind.PRODUCT_PULL,response(body(event),true));assertEquals(true,r.get("verified"));assertEquals("SUCCESS",r.get("business_state"));assertEquals(event,r.get("latest_event_id"));verify(base.handler,times(1)).handle(anyString(),anyMap());assertFalse(r.containsKey("body"));}
 @Test void forgedCompanyAndMalformedBodyAreAnonymous()throws Exception{String event=base.event();Map<String,Object> e=base.out.envelope(base.out.row("QM",event));e.put("company_code","OTHER");Map<String,Object> r=collector.inspect(QueueKind.PRODUCT_PULL,response(collector.json.writeValueAsBytes(e),true));assertEquals(false,r.get("verified"));assertEquals("",r.get("company_code"));assertEquals("INVALID_ENVELOPE",r.get("death_reason"));r=collector.inspect(QueueKind.PRODUCT_PULL,response("not-json".getBytes(),true));assertEquals("",r.get("event_id"));verifyNoInteractions(base.handler);}
 @Test void directlyInjectedMessagesAreNotTrustedAsBrokerDeadLetters()throws Exception{String event=base.event();Map<String,Object> r=collector.inspect(QueueKind.PRODUCT_PULL,response(body(event),false));assertEquals("UNVERIFIED_SOURCE",r.get("death_reason"));assertEquals(false,r.get("verified"));verifyNoInteractions(base.handler);}
 @Test void acknowledgementRequiresDurableReportAndRetryDoesNotLoseMessage()throws Exception{String event=base.event();Channel channel=mock(Channel.class);when(channel.basicGet(QueueKind.PRODUCT_PULL.dead(),false)).thenReturn(response(body(event),true),null);doAnswer(i->((ChannelCallback<?>)i.getArgument(0)).doInRabbit(channel)).when(collector.rabbit).execute(any(ChannelCallback.class));when(collector.control.deadReport(anyMap(),anyString())).thenReturn(R.ok(true));collector.drain(QueueKind.PRODUCT_PULL);verify(channel).basicAck(1,false);reset(channel);when(channel.basicGet(QueueKind.PRODUCT_PULL.dead(),false)).thenReturn(response(body(event),true));when(collector.control.deadReport(anyMap(),anyString())).thenThrow(new RuntimeException("control down"));collector.drain(QueueKind.PRODUCT_PULL);verify(channel).basicNack(1,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());verifyNoInteractions(base.handler);}
}
