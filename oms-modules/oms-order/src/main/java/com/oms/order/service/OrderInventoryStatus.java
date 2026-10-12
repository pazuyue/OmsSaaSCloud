package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Separates logical protection from warehouse inventory receipts. */
public final class OrderInventoryStatus {
    private OrderInventoryStatus(){}
    public static void decorate(Map<String,Object> row){
        long outbound=number(row,"shipped_quantity"),required=number(row,"demand_quantity");
        row.put("logical_inventory_status",outbound>0?(outbound>=required&&required>0?"CONSUMED":"PART_CONSUMED"):row.get("inventory_status"));
        row.put("logical_outbound_quantity",outbound);row.put("logical_active_quantity",Math.max(0,number(row,"reserved_quantity")-outbound));
        long occupied=number(row,"allocation_quantity");boolean released="VOIDED".equals(text(row,"status"));
        row.put("warehouse_required_quantity",number(row,"demand_quantity"));row.put("warehouse_occupied_quantity",released?0:Math.max(0,occupied-outbound));row.put("warehouse_outbound_quantity",number(row,"shipped_quantity"));
        String allocation=text(row,"allocation_status");
        row.put("warehouse_inventory_status",released?(occupied>0?"RELEASED":"NOT_OCCUPIED"):outbound>0?(outbound>=required&&required>0?"OUTBOUND":"PART_OUTBOUND"):Arrays.asList("PROCESSING","UNCERTAIN").contains(allocation)?"UNKNOWN":occupied>0?("FULL".equals(allocation)?"OCCUPIED":"PART_OCCUPIED"):Arrays.asList("WAIT_ALLOCATION","VOIDING").contains(text(row,"status"))?"NOT_OCCUPIED":"UNKNOWN");
    }
}
