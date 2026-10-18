package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** An exchange owns its replacement shipment. The original SO remains the transaction anchor. */
final class OrderShipmentOwner {
    private OrderShipmentOwner() {}
    static boolean exchange(Map<String,Object> shipment) { return "EXCHANGE".equals(text(shipment,"business_type")); }
    static void apply(OrderRepository repository,String company,Map<String,Object> shipment,Map<String,Object> order,Map<String,Object> platform,boolean locked) {
        if(!exchange(shipment))return;
        require(text(order,"refund_block_reason").isEmpty(),text(order,"refund_block_reason"));
        Map<String,Object> e=repository.db().query("SELECT * FROM oms_exchange WHERE company_code=? AND id=? AND order_id=?"+(locked?" FOR UPDATE":""),ROW,company,shipment.get("exchange_id"),order.get("id")).stream().findFirst().orElseThrow(()->new IllegalArgumentException("换出发货单缺少所属换货单"));
        ExchangeFulfillment.planUnchanged(e);if(shipment.containsKey("stock_reference"))ExchangeRounds.guardShipment(e,shipment);
        require(e.get("fulfillment_line_id")!=null&&Arrays.asList("PROCESSING","APPROVED").contains(text(e,"state")),"换货单当前不允许新增履约，请核对本次申请");
        require("PASSED".equals(text(e,"return_qc"))&&("INTERNAL".equals(text(e,"source_kind"))||"12".equals(text(e,"platform_status"))),"换货单尚未完成退回验收和平台收货确认");
        Map<String,Object> recipient=ExchangeRounds.recipient(e);
        Map<String,Object> trade=new LinkedHashMap<>();
        for(String[] pair:new String[][]{{"name","receiver_name"},{"mobile","receiver_mobile"},{"province","receiver_state"},{"city","receiver_city"},{"area","receiver_district"},{"town","receiver_town"},{"detailAddress","receiver_address"},{"oaid","oaid"}})trade.put(pair[1],recipient.get(pair[0]));
        trade.put("orders",map("order",Collections.emptyList()));
        platform.put("processing_payload",encode(map("trade_fullinfo_get_response",map("trade",trade))));
        platform.put("platform_status","WAIT_SELLER_SEND_GOODS");platform.put("change_pending",0);platform.put("fulfillment_block_reason","");
        order.put("status","WAIT_ALLOCATION");order.put("review_status","APPROVED");order.put("review_at",e.get("reviewed_at"));
        shipment.put("owner_sn",e.get("exchange_sn"));
    }
}
