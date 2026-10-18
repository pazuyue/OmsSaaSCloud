package com.oms.supplychain.service.wms;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Shared deliveryOrder fields; provider adapters own envelope, signing and response parsing. */
public final class WmsOutboundPayload {
    private WmsOutboundPayload(){}
    @SuppressWarnings("unchecked") public static Map<String,Object> request(String action,Map<String,Object> task,Map<String,Object> payload){
        if("CANCEL".equals(action))return map("ownerCode",task.get("external_owner"),"warehouseCode",task.get("external_warehouse"),"orderCode",task.get("shipment_sn"),"orderId",task.get("external_order"),"orderType","EXCHANGE".equals(payload.get("business_type"))?"HHCK":"JYCK","cancelReason",task.get("cancel_reason"));
        if("QUERY".equals(action))return map("ownerCode",task.get("external_owner"),"warehouseCode",task.get("external_warehouse"),"orderCode",task.get("shipment_sn"),"orderId",task.get("external_order"),"page",1,"pageSize",100);
        require("CREATE".equals(action),"不支持的发货单动作");
        List<Map<String,Object>> lines=(List<Map<String,Object>>)payload.get("lines"),details=new ArrayList<>();
        for(Map<String,Object> line:lines)details.add(map("orderLineNo",String.valueOf(line.get("id")),"itemCode",line.get("sku_sn"),"itemName",line.get("goods_name"),"ownerCode",task.get("external_owner"),"planQty",String.valueOf(line.get("quantity")),"inventoryType","ZP","batchCode",line.get("batch_code"),"sourceOrderCode",payload.get("platform_tid"),"subSourceOrderCode",line.get("platform_oid")));
        Map<String,Object> head=map("deliveryOrderCode",task.get("shipment_sn"),"orderType","EXCHANGE".equals(payload.get("business_type"))?"HHCK":"JYCK","warehouseCode",task.get("external_warehouse"),"ownerCode",task.get("external_owner"),"sourcePlatformCode","TM","sourceOrderCode",payload.get("platform_tid"),"createTime",payload.get("create_time"),"placeOrderTime",payload.get("place_order_time"),"operateTime",payload.get("operate_time"),"shopNick",payload.get("shop_name"),"sellerNick",payload.get("seller_nick"),"logisticsCode",payload.get("warehouse_carrier_code"),"logisticsName",payload.get("carrier_name"),"totalOrderLines",String.valueOf(details.size()),"receiverInfo",payload.get("receiver"));
        return map("deliveryOrder",head,"orderLines",details);
    }
    public static String xml(Map<String,Object> request){StringBuilder out=new StringBuilder("<request>");request.forEach((key,value)->append(out,key,value));return out.append("</request>").toString();}
    private static void append(StringBuilder out,String key,Object value){
        if(value instanceof Map){out.append('<').append(key).append('>');((Map<?,?>)value).forEach((k,v)->append(out,k.toString(),v));out.append("</").append(key).append('>');}
        else if(value instanceof List){out.append('<').append(key).append('>');for(Object line:(List<?>)value)append(out,"orderLine",line);out.append("</").append(key).append('>');}
        else out.append(QimenProtocol.tag(key,value));
    }
}
