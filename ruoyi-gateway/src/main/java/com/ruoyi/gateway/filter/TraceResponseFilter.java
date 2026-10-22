package com.ruoyi.gateway.filter;

import com.ruoyi.common.core.tracing.Tracing;
import org.apache.skywalking.apm.toolkit.webflux.WebFluxSkyWalkingTraceContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Capture on the request thread; response completion may run on another Reactor thread. */
@Component
public class TraceResponseFilter implements GlobalFilter, Ordered {
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String id = WebFluxSkyWalkingTraceContext.traceId(exchange);
        if (id == null || id.isEmpty() || "N/A".equals(id)) id = Tracing.traceId();
        final String traceId = id;
        if (traceId != null && !"N/A".equals(traceId)) {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
