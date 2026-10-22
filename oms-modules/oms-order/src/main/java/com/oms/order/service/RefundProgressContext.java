package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Local, read-only projection. Approval, platform commands and funds are separate facts. */
final class RefundProgressContext {
    private RefundProgressContext() {}

    static void attach(OrderRepository repository, String company, Map<String,Object> refund, Map<String,Object> platform) {
        long id = number(refund,"id");
        List<Map<String,Object>> plans = repository.db().query(
            "SELECT p.id,p.gift_plan_id,p.sku_sn,ol.goods_name,p.quantity,p.accepted_quantity,p.posted_quantity,p.waived_quantity," +
            "p.state,p.qc_state,p.parcel_state,p.parcel_resolution,p.auto_dispatch_state,p.auto_dispatch_message,p.last_error,p.create_time," +
            "IF(ol.stocked_bundle_snapshot IS NULL,'件','套') stock_unit,p.result_json " +
            "FROM oms_refund_return_plan p LEFT JOIN oms_order_shipment_line sl ON sl.company_code=p.company_code AND sl.id=p.shipment_line_id " +
            "LEFT JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id " +
            "WHERE p.company_code=? AND p.refund_id=? ORDER BY p.id LIMIT 1001", ROW, company,id);
        boolean truncated = plans.size() > 1000;
        if (truncated) plans.remove(plans.size()-1);
        for (Map<String,Object> plan : plans) {
            Map<String,Object> actual = obj(plan.remove("result_json"));
            plan.put("has_document", !actual.isEmpty());
            plan.put("pending_count", number(actual,"pending_review_count") + number(actual,"parcel_pending_count"));
            int active = 0;
            Object raw = actual.get("documents");
            Collection<?> documents = raw instanceof Collection ? (Collection<?>)raw : actual.isEmpty() ? Collections.emptyList() : Collections.singletonList(actual);
            Map<String,Object> current = documents.isEmpty() ? actual : obj(documents.iterator().next());
            plan.put("dispatch_state",current.get("dispatch_state"));
            plan.put("cancel_state",current.get("cancel_state"));
            if (text(plan,"last_error").isEmpty()) plan.put("last_error",current.get("last_error"));
            for (Object item : documents) {
                Map<String,Object> document = obj(item);
                if (!"CONFIRMED".equals(text(document,"cancel_state")) && !"COMPLETED".equals(text(document,"receipt_state"))) active++;
            }
            plan.put("active_documents",active);
        }
        refund.put("progress_context",m("plans",plans,"plans_truncated",truncated));

        // A source observation is never presented as an OMS consent command.
        List<Map<String,Object>> observations = repository.db().query(
            "SELECT id,source_modified,jdp_modified,outcome,create_time,payload FROM oms_platform_refund_snapshot " +
            "WHERE company_code=? AND platform_refund_id=? ORDER BY id DESC LIMIT 30", ROW, company,platform.get("id"));
        for (Map<String,Object> observation : observations) {
            Object payload = observation.remove("payload");
            try {
                Map<String,Object> parsed = parse(String.valueOf(payload));
                observation.put("platform_status",parsed.get("status"));
                observation.put("refund_fee",parsed.get("refund_fee"));
                observation.put("refund_version",parsed.get("refund_version"));
            } catch (IllegalArgumentException invalid) {
                observation.put("platform_status",null);
            }
        }
        List<Map<String,Object>> operations = repository.db().query(
            "SELECT id,request_id,action,state,source_version,platform_version,fee,reason,actor,error_code,result_message,create_time " +
            "FROM oms_refund_operation WHERE company_code=? AND refund_id=? ORDER BY id DESC LIMIT 30", ROW,company,id);
        refund.put("platform_interaction",m("execution_supported",false,"mode","SOURCE_SYNC","operations",operations,"observations",observations,
            "message","同意退款、同意退货、拒绝退款需在平台处理；OMS当前同步平台结果，本地审核不会向平台提交这些操作。"));
    }
}
