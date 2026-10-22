package com.oms.messaging;

import com.ruoyi.common.core.tracing.Tracing;
import org.apache.skywalking.apm.toolkit.trace.ActiveSpan;
import org.apache.skywalking.apm.toolkit.trace.Tracer;
import org.apache.skywalking.apm.toolkit.trace.ContextCarrierRef;
import org.apache.skywalking.apm.toolkit.trace.CarrierItemRef;
import org.slf4j.MDC;
import java.util.LinkedHashMap;
import java.util.Map;

/** One bounded operation per message. Never persist a live context across Outbox delays. */
final class MessageTrace implements AutoCloseable {
    private final Map<String, String> previous = new LinkedHashMap<>();

    private MessageTrace(String stage, String company, Map<String, Object> row, Map<String, Object> headers) {
        String operation = "oms.message." + stage + "/" + MessageOutbox.s(row, "message_type");
        if (headers != null) Tracer.createEntrySpan(operation, carrier(headers));
        else if (Tracing.traceId() == null) Tracer.createEntrySpan(operation, null);
        else Tracer.createLocalSpan(operation);
        bind("companyCode", company);
        bind("businessId", MessageOutbox.s(row, "business_id"));
        bind("eventId", MessageOutbox.s(row, "event_id"));
        bind("businessTraceId", MessageOutbox.s(row, "trace_id"));
        bind("swTraceId", Tracing.traceId());
        ActiveSpan.tag("oms.company_code", company);
        for (String key : new String[]{"event_id", "message_type", "business_id", "trace_id", "source_trace_id"}) {
            String value = MessageOutbox.s(row, key);
            if (!value.isEmpty()) ActiveSpan.tag("oms." + key, value);
        }
    }

    static MessageTrace start(String stage, String company, Map<String, Object> row) {
        return new MessageTrace(stage, company, row, null);
    }

    static MessageTrace receive(String company, Map<String, Object> row, Map<String, Object> headers) {
        return new MessageTrace("consume", company, row, headers);
    }

    private static ContextCarrierRef carrier(Map<String, Object> headers) {
        ContextCarrierRef carrier = new ContextCarrierRef();
        CarrierItemRef item = carrier.items();
        while (item.hasNext()) {
            item = item.next();
            Object value = headers.get(item.getHeadKey());
            if (value != null) item.setHeadValue(value.toString());
        }
        return carrier;
    }

    static void send(String type, String peer, Map<String, Object> headers, Runnable send) {
        ContextCarrierRef carrier = new ContextCarrierRef();
        Tracer.createExitSpan("oms.message.send/" + type, carrier, peer);
        try {
            CarrierItemRef item = carrier.items();
            while (item.hasNext()) {
                item = item.next();
                headers.put(item.getHeadKey(), item.getHeadValue());
            }
            send.run();
        } finally { Tracer.stopSpan(); }
    }

    private void bind(String key, String value) {
        previous.put(key, MDC.get(key));
        if (value == null || value.isEmpty()) MDC.remove(key); else MDC.put(key, value);
    }

    void result(String state, String problem) {
        ActiveSpan.tag("oms.result", state);
        ActiveSpan.tag("oms.problem_code", problem);
        if ("FAILED".equals(state) || "UNCERTAIN".equals(state)) ActiveSpan.error("message operation failed");
    }

    void error(Exception error) {
        // Exception messages can include platform credentials or payloads.
        ActiveSpan.error(error.getClass().getSimpleName());
    }

    @Override public void close() {
        try { Tracer.stopSpan(); }
        finally { previous.forEach((key, value) -> { if (value == null) MDC.remove(key); else MDC.put(key, value); }); }
    }
}
