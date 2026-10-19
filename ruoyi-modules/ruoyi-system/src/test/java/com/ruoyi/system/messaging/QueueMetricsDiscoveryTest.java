package com.ruoyi.system.messaging;
import org.junit.jupiter.api.*;import org.springframework.cloud.client.DefaultServiceInstance;import org.springframework.cloud.client.discovery.DiscoveryClient;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class QueueMetricsDiscoveryTest {
 QueueMetricsDiscovery service;
 @BeforeEach void setup(){service=new QueueMetricsDiscovery();service.discovery=mock(DiscoveryClient.class);}
 @Test void discoveryIncludesEveryReplicaButOnlyRegisteredMessagingServices(){when(service.discovery.getInstances("ruoyi-system")).thenReturn(Collections.singletonList(new DefaultServiceInstance("s","ruoyi-system","system",9201,false)));when(service.discovery.getInstances("oms-channel")).thenReturn(Arrays.asList(new DefaultServiceInstance("a","oms-channel","10.0.0.1",9304,false),new DefaultServiceInstance("b","oms-channel","10.0.0.2",9304,false)));when(service.discovery.getInstances("oms-order")).thenReturn(Collections.singletonList(new DefaultServiceInstance("o","oms-order","order",9305,false)));List<Map<String,Object>> targets=service.targets();assertEquals(4,targets.size());assertTrue(targets.toString().contains("10.0.0.2:9304"));verify(service.discovery,never()).getInstances("oms-inventory");}
 @Test void unavailableDiscoveryDoesNotReturnAnEmptyListThatDeletesKnownTargets(){assertThrows(IllegalStateException.class,service::targets);when(service.discovery.getInstances("ruoyi-system")).thenReturn(Collections.singletonList(new DefaultServiceInstance("s","ruoyi-system","http://bad/path",9201,false)));assertThrows(IllegalStateException.class,service::targets);}
 @Test void serviceDiscoveryEndpointRequiresTrustedInternalIdentity()throws Exception{assertNotNull(com.ruoyi.system.controller.QueueControlController.class.getMethod("metricsTargets").getAnnotation(com.ruoyi.common.security.annotation.InnerAuth.class));}
}
