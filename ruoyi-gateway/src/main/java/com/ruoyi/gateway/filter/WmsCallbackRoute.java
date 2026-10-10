package com.ruoyi.gateway.filter;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;

/** Public only for provider-signed WMS callbacks; no user or internal-source identity is trusted. */
final class WmsCallbackRoute {
    private WmsCallbackRoute(){}
    static boolean matches(ServerHttpRequest request){return request.getMethod()==HttpMethod.POST&&request.getURI().getPath().matches("^/supplychain/wmsCallback/[A-Za-z0-9_-]{1,64}/[a-f0-9]{32}$");}
}
