package com.oms.order.service;

import java.util.Map;
import static com.oms.order.service.OrderValues.*;

/** Fulfillment comes from posted receipt quantities, never from a WMS query status. */
final class OrderShipmentWarehouseProgress {
    private OrderShipmentWarehouseProgress() {}
    static String confirmedMessage(long planned,long shipped,long packaged,long packages) {
        if(shipped<=0)return "";
        return (shipped>=planned?"全部出库":"部分出库")+"，实际出库 "+shipped+" / "+planned+" 件，剩余 "+Math.max(0,planned-shipped)+" 件；已记录 "+packages+" 个包裹，包裹覆盖 "+packaged+" 件"+(packaged<shipped?"，仍有出库商品待补齐包裹":"；库存扣账完成");
    }
    static void decorate(Map<String,Object> row) {
        long shipped=number(row,"shipped_quantity");
        row.put("wms_fulfillment_state",shipped>0?(shipped>=number(row,"quantity")?"SHIPPED":"PART_SHIPPED"):text(row,"wms_dispatch_state"));
        String message=confirmedMessage(number(row,"quantity"),shipped,number(row,"packaged_quantity"),number(row,"package_count"));
        if(message.isEmpty()) {
            message=!text(row,"wms_error").isEmpty()?text(row,"wms_error"):text(row,"wms_query_message");
            if(message.isEmpty()&&"ACCEPTED".equals(text(row,"wms_dispatch_state")))message="仓库已确认接收，等待实际出库明细回传";
        } else if(number(row,"outbound_unconfirmed_count")>0)message+="；另有出库回传待核对";
        if(!text(row,"wms_warehouse_status").isEmpty())message="仓库状态："+text(row,"wms_warehouse_status")+"；"+message;
        row.put("wms_progress_message",message);
    }
}
