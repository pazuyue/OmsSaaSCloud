package com.oms.order.service;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
/** A separate application guard survives a user releasing the ordinary manual hold. */
final class OrderExchangeGuard {
    private OrderExchangeGuard(){}
    static String reason(OrderRepository repository,String company,long order,boolean locked){
        List<String> refund=repository.db().queryForList("SELECT COALESCE(NULLIF(refund_block_reason,''),exchange_block_reason) FROM oms_order WHERE company_code=? AND id=?"+(locked?" FOR UPDATE":""),String.class,company,order);if(!refund.isEmpty()&&!refund.get(0).isEmpty())return refund.get(0);
        List<Map<String,Object>> rows=repository.db().query("SELECT id,exchange_sn FROM oms_exchange WHERE company_code=? AND ((order_id=? AND source_kind='INTERNAL' AND (fulfillment_mode='UNSHIPPED' OR fulfillment_mode='' AND source_shipment_line_id IS NULL) AND fulfillment_order_id IS NULL AND state NOT IN ('CANCELED','REJECTED')) OR (fulfillment_order_id=? AND source_kind='INTERNAL' AND (fulfillment_mode='UNSHIPPED' OR fulfillment_mode='' AND source_shipment_line_id IS NULL) AND fulfillment_line_id IS NULL AND state IN ('BLOCKED','CANCELED','REJECTED'))) ORDER BY id LIMIT 1"+(locked?" FOR UPDATE":""),ROW,company,order,order);

        return rows.isEmpty()?"":"换货处理 "+text(rows.get(0),"exchange_sn")+" 尚未核对，请到换货管理处理";
    }
    static void requireReady(OrderRepository repository,String company,long order,boolean locked){String reason=reason(repository,company,order,locked);require(reason.isEmpty(),reason);}
}
