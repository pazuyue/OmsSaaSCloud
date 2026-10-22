package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Read-only decoration from the immutable order/shipment source, never today's product tags. */
final class RefundReturnPresentation {
    private RefundReturnPresentation() {}

    static void sources(OrderRepository repository, String company, List<Map<String,Object>> rows, String sourceKey) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String,Object> row : rows) if (number(row, sourceKey) > 0) ids.add(number(row, sourceKey));
        if (ids.isEmpty()) return;
        List<Object> args = new ArrayList<>(); args.add(company); args.addAll(ids);
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Map<String,Object>> sources = repository.db().query(
            "SELECT sl.id source_id,sl.goods_name,sl.shipment_id,s.shipment_sn,l.platform_oid,l.gift_plan_id source_gift_plan_id," +
            "IF(l.stocked_bundle_snapshot IS NULL,'件','套') stock_unit," +
            "CASE WHEN l.stocked_bundle_snapshot IS NOT NULL THEN 'STOCKED_BUNDLE' WHEN b.business_type='LUCKY_BAG' THEN 'LUCKY_BAG' WHEN b.id IS NOT NULL THEN 'BUNDLE' ELSE 'NORMAL' END source_kind," +
            "b.id source_bundle_id,b.goods_name parent_goods_name,b.sku_sn parent_sku_sn " +
            "FROM oms_order_shipment_line sl JOIN oms_order_shipment s ON s.company_code=sl.company_code AND s.id=sl.shipment_id " +
            "JOIN oms_order_line l ON l.company_code=sl.company_code AND l.id=sl.order_line_id " +
            "LEFT JOIN oms_order_bundle b ON b.company_code=l.company_code AND b.order_id=l.order_id AND b.id=l.bundle_id " +
            "WHERE sl.company_code=? AND sl.id IN (" + marks + ")", ROW, args.toArray());
        Map<Long,Map<String,Object>> byId = new HashMap<>();
        for (Map<String,Object> source : sources) byId.put(number(source,"source_id"), source);
        for (Map<String,Object> row : rows) {
            Map<String,Object> source = byId.get(number(row,sourceKey));
            if (source == null) continue;
            row.putAll(source);
            row.put("item_kind", number(source,"source_gift_plan_id") > 0 ? "GIFT" : "PURCHASE");
        }
    }
}
