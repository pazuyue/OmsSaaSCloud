package com.ruoyi.modules.monitor.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import de.codecentric.boot.admin.server.cloud.discovery.InstanceDiscoveryListener;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosDiscoveryRefreshConfigurationTest
{
    @Test
    void discoversServicesRegisteredAfterMonitorStartupAndRemovesDepartedInstances()
    {
        DiscoveryClient discovery = mock(DiscoveryClient.class);
        InstanceRegistry registry = mock(InstanceRegistry.class);
        InstanceRepository repository = mock(InstanceRepository.class);
        Map<InstanceId, Instance> registered = new ConcurrentHashMap<>();
        ServiceInstance monitor = new DefaultServiceInstance("monitor", "ruoyi-monitor", "localhost", 9100, false);
        ServiceInstance inventory = new DefaultServiceInstance("inventory", "oms-inventory", "localhost", 9303, false);
        AtomicReference<List<ServiceInstance>> services = new AtomicReference<>(Collections.singletonList(monitor));
        when(discovery.getServices()).thenAnswer(call -> services.get().stream()
                .map(ServiceInstance::getServiceId).collect(java.util.stream.Collectors.toList()));
        when(discovery.getInstances(anyString())).thenAnswer(call -> services.get().stream()
                .filter(service -> service.getServiceId().equals(call.getArgument(0)))
                .collect(java.util.stream.Collectors.toList()));
        when(repository.findAll()).thenAnswer(call -> Flux.fromIterable(registered.values()));
        when(registry.register(any(Registration.class))).thenAnswer(call -> {
            Registration registration = call.getArgument(0);
            InstanceId id = InstanceId.of(registration.getName());
            registered.put(id, Instance.create(id).register(registration));
            return Mono.just(id);
        });
        when(registry.deregister(any(InstanceId.class))).thenAnswer(call -> {
            InstanceId id = call.getArgument(0);
            registered.remove(id);
            return Mono.just(id);
        });

        new ApplicationContextRunner()
                .withUserConfiguration(NacosDiscoveryRefreshConfiguration.class)
                .withPropertyValues("spring.cloud.nacos.discovery.watch-delay=20")
                .withBean(InstanceDiscoveryListener.class,
                        () -> new InstanceDiscoveryListener(discovery, registry, repository))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    awaitRegistered(registered, "ruoyi-monitor");
                    services.set(Arrays.asList(monitor, inventory));
                    awaitRegistered(registered, "ruoyi-monitor", "oms-inventory");
                    services.set(Collections.singletonList(monitor));
                    awaitRegistered(registered, "ruoyi-monitor");
                });
    }

    @Test
    void respectsDisabledDiscovery()
    {
        for (String property : Arrays.asList("spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.boot.admin.discovery.enabled=false"))
        {
            new ApplicationContextRunner()
                    .withUserConfiguration(NacosDiscoveryRefreshConfiguration.class)
                    .withPropertyValues(property)
                    .run(context -> assertThat(context).doesNotHaveBean(NacosDiscoveryRefreshConfiguration.class));
        }
    }

    private static void awaitRegistered(Map<InstanceId, Instance> registered, String... names)
    {
        Set<InstanceId> expected = new HashSet<>();
        for (String name : names)
        {
            expected.add(InstanceId.of(name));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!registered.keySet().equals(expected) && System.nanoTime() < deadline)
        {
            try
            {
                Thread.sleep(20);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for discovery", e);
            }
        }
        assertThat(registered.keySet()).containsExactlyInAnyOrderElementsOf(expected);
    }
}
