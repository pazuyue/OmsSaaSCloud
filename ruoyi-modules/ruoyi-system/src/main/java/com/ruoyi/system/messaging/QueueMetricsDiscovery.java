package com.ruoyi.system.messaging;
import org.springframework.stereotype.Component;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import javax.annotation.Resource;import java.util.*;
/** Private Prometheus HTTP discovery, restricted to the three messaging applications. */
@Component public class QueueMetricsDiscovery {
 @Resource DiscoveryClient discovery;
 public List<Map<String,Object>> targets(){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(String service:Arrays.asList("ruoyi-system","oms-channel","oms-order")){List<ServiceInstance> instances=discovery.getInstances(service);if(instances==null)throw new IllegalStateException("监控服务发现暂不可用");for(ServiceInstance node:instances){String host=node.getHost();int port=node.getPort();if(host==null||!host.matches("[A-Za-z0-9_.:-]{1,255}")||port<1||port>65535)throw new IllegalStateException("监控实例地址无效");String target=(host.contains(":")?"["+host+"]":host)+":"+port;Map<String,Object> labels=new LinkedHashMap<>();labels.put("application",service);labels.put("__scheme__",node.isSecure()?"https":"http");Map<String,Object> entry=new LinkedHashMap<>();entry.put("targets",Collections.singletonList(target));entry.put("labels",labels);result.put(service+":"+target,entry);}}if(result.isEmpty())throw new IllegalStateException("监控服务发现尚未就绪，保留上次目标");return new ArrayList<>(result.values());}
}
