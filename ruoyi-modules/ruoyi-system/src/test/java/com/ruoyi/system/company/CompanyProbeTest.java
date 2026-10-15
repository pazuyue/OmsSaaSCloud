package com.ruoyi.system.company;
import com.alibaba.cloud.nacos.*;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import static com.ruoyi.system.company.CompanyServices.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CompanyProbeTest {
    @Test void manualChecksHaveAtMostThreeConcurrentRequestsAndCheckEveryInstance() throws Exception {
        NacosCompanyRuntimeProbe probe=new NacosCompanyRuntimeProbe();NacosServiceManager manager=mock(NacosServiceManager.class);NacosDiscoveryProperties discovery=mock(NacosDiscoveryProperties.class);when(discovery.getNacosProperties()).thenReturn(new Properties());when(discovery.getGroup()).thenReturn("DEFAULT_GROUP");NamingService naming=mock(NamingService.class);RestTemplate http=mock(RestTemplate.class);
        ReflectionTestUtils.setField(probe,"nacos",manager);ReflectionTestUtils.setField(probe,"discovery",discovery);ReflectionTestUtils.setField(probe,"http",http);
        when(manager.getNamingService(any(Properties.class))).thenReturn(naming);Instance first=new Instance();first.setIp("127.0.0.1");first.setPort(9301);first.setHealthy(true);first.setEnabled(true);
        for(String app:APPS.values())when(naming.getAllInstances(app,discovery.getGroup())).thenReturn(Arrays.asList(first,first));
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger(),calls=new AtomicInteger();
        when(http.postForObject(any(URI.class),any(),eq(R.class))).thenAnswer(i->{int concurrent=active.incrementAndGet();max.accumulateAndGet(concurrent,Math::max);calls.incrementAndGet();try{Thread.sleep(30);String service=APPS.values().stream().skip((calls.get()-1)/2).findFirst().orElse("oms-order");return R.ok(map("service",service,"schema_ready",true,"available_aliases",Collections.singletonList("master")));}finally{active.decrementAndGet();}});
        // Identity may fail deliberately; all ten registered instances must still be bounded and checked.
        try{Map<String,Object> report=probe.inspect("UNIT_A",Collections.emptyMap(),"",false);assertEquals(10,calls.get());assertTrue(max.get()<=3);assertTrue(max.get()>1);assertTrue(number(report,"duration_ms")<3000);}finally{probe.close();}
    }
    @Test void companyMaintenanceRequiresPlatformIdentity(){com.ruoyi.system.controller.CompanyController controller=new com.ruoyi.system.controller.CompanyController();com.ruoyi.common.core.context.SecurityContextHolder.setUserId("2");try{assertThrows(IllegalArgumentException.class,()->controller.detail("QM"));}finally{com.ruoyi.common.core.context.SecurityContextHolder.remove();}}
}
