package com.ruoyi.modules.monitor.config;

import java.util.concurrent.atomic.AtomicLong;
import com.alibaba.cloud.nacos.ConditionalOnNacosDiscoveryEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ConditionalOnDiscoveryEnabled;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Refresh Admin discovery after startup, including services registered later.
 * NacosWatch in Spring Cloud Alibaba 2021.0.5.0 no longer publishes HeartbeatEvent.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnDiscoveryEnabled
@ConditionalOnNacosDiscoveryEnabled
@ConditionalOnProperty(prefix = "spring.boot.admin.discovery", name = "enabled", matchIfMissing = true)
public class NacosDiscoveryRefreshConfiguration
{
    private final ApplicationEventPublisher publisher;

    private final AtomicLong heartbeat = new AtomicLong();

    public NacosDiscoveryRefreshConfiguration(ApplicationEventPublisher publisher)
    {
        this.publisher = publisher;
    }

    @Scheduled(initialDelayString = "${spring.cloud.nacos.discovery.watch-delay:30000}",
            fixedDelayString = "${spring.cloud.nacos.discovery.watch-delay:30000}")
    public void refreshDiscovery()
    {
        // Admin's HeartbeatMonitor only refreshes when the event value changes.
        publisher.publishEvent(new HeartbeatEvent(this, heartbeat.incrementAndGet()));
    }
}
