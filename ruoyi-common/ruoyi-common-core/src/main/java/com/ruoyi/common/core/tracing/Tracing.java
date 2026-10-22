package com.ruoyi.common.core.tracing;

import org.apache.skywalking.apm.toolkit.trace.TraceContext;

/** Trace identifiers are optional metadata; business identifiers remain authoritative. */
public final class Tracing {
    private Tracing() { }

    public static String traceId() {
        String id = TraceContext.traceId();
        return id == null || id.isEmpty() || "N/A".equals(id) || "Ignored_Trace".equals(id)
                || id.length() > 256 ? null : id;
    }
}
