package com.oms.messaging;
import org.junit.jupiter.api.*;import java.util.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class QueueSupervisorTest {
 QueueSupervisor q;QueueSupervisor.State state;
 static class FakeContainer extends SimpleMessageListenerContainer {int starts,stops,actual;boolean fail;
 FakeContainer(){super(mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class));}
 @Override public void start(){if(fail)throw new IllegalStateException("test");starts++;org.springframework.test.util.ReflectionTestUtils.setField(this,"running",true);}
 @Override public void stop(){stops++;setNotRunning();}
 @Override public int getActiveConsumerCount(){return actual;}
 }

 Map<String,Object> policy(long version,String mode){Map<String,Object> p=new HashMap<>();p.put("revision",version);p.put("concurrency",2);p.put("prefetch",1);p.put("desired_mode",mode);return p;}
 @BeforeEach void setup(){q=new QueueSupervisor();state=new QueueSupervisor.State();state.policy=policy(1,"RUNNING");state.applied=1;state.accepting=true;state.status="RUNNING";state.container=new FakeContainer();q.states.put("PRODUCT_PULL",state);q.configOk=System.currentTimeMillis();}
 @AfterEach void close(){q.scheduler.shutdownNow();q.consumers.shutdownNow();}
 @Test void mqAndCompensationShareTheSameBoundedAdmission(){QueueSupervisor.Permit first=q.enter("PRODUCT_PULL"),second=q.enter("PRODUCT_PULL");assertNotNull(first);assertNotNull(second);assertNull(q.enter("PRODUCT_PULL"));first.close();assertNotNull(q.enter("PRODUCT_PULL"));second.close();}
 @Test void expiredOrMissingControlConfigurationFailsClosed(){q.configOk=System.currentTimeMillis()-21000;assertNull(q.enter("PRODUCT_PULL"));q.configOk=System.currentTimeMillis();assertNull(q.enter("UNKNOWN"));state.applied=0;assertNull(q.enter("PRODUCT_PULL"));}
 @Test void pauseStopsNewAdmissionsWhileExistingWorkMayFinish(){QueueSupervisor.Permit inFlight=q.enter("PRODUCT_PULL");org.springframework.test.util.ReflectionTestUtils.setField(state.container,"running",true);q.apply(state,policy(2,"PAUSED"));assertEquals(1,((FakeContainer)state.container).stops);assertNull(q.enter("PRODUCT_PULL"));assertEquals(1,state.active.get());inFlight.close();assertEquals(0,state.active.get());assertEquals(2,state.applied);assertEquals(0,((FakeContainer)state.container).starts);}
 @Test void restartDoesNotStartPersistentlyPausedQueue(){state.applied=0;q.apply(state,policy(2,"PAUSED"));assertFalse(q.enabled("PRODUCT_PULL"));assertEquals(0,((FakeContainer)state.container).starts);}
 @Test void failedConfigurationIsNotReportedAsApplied(){((FakeContainer)state.container).fail=true;q.apply(state,policy(2,"RUNNING"));assertEquals(1,state.applied);assertFalse(q.enabled("PRODUCT_PULL"));assertEquals("ERROR",state.status);}
 @Test void eachInstanceReconcilesTheDesiredRevisionIndependently(){QueueSupervisor.State other=new QueueSupervisor.State();other.container=new FakeContainer();q.apply(state,policy(3,"PAUSED"));assertEquals(0,other.applied);q.apply(other,policy(3,"PAUSED"));assertEquals(3,state.applied);assertEquals(3,other.applied);}
 @Test void runningIsConfirmedOnlyWhenActualConsumerCountMatchesPolicy(){q.outbox=new MessageOutbox();q.outbox.service="channel";q.control=mock(QueueControlClient.class);((FakeContainer)state.container).actual=1;q.heartbeat();org.mockito.ArgumentCaptor<Map> body=org.mockito.ArgumentCaptor.forClass(Map.class);verify(q.control).heartbeat(body.capture(),anyString());Map<?,?> row=(Map<?,?>)((List<?>)body.getValue().get("queues")).get(0);assertEquals("APPLYING",row.get("state"));((FakeContainer)state.container).actual=2;clearInvocations(q.control);q.heartbeat();verify(q.control).heartbeat(body.capture(),anyString());row=(Map<?,?>)((List<?>)body.getValue().get("queues")).get(0);assertEquals("RUNNING",row.get("state"));}
}
