package com.oms.order.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderInventoryStatusTest {
    @Test void logicalReservationNeverImpliesWarehouseOccupation(){
        for(String logical:Arrays.asList("WAIT_RESERVE","PART_RESERVED","RESERVED")){
            Map<String,Object> row=new HashMap<>();row.put("status","WAIT_ALLOCATION");row.put("inventory_status",logical);
            OrderInventoryStatus.decorate(row);
            assertEquals(logical,row.get("logical_inventory_status"));
            assertEquals("NOT_OCCUPIED",row.get("warehouse_inventory_status"));
            assertEquals(logical,row.get("inventory_status"));
        }
    }
    @Test void logicalReleaseDoesNotClaimWarehouseInventoryWasReleased(){
        Map<String,Object> row=new HashMap<>();row.put("status","VOIDED");row.put("inventory_status","RELEASED");
        OrderInventoryStatus.decorate(row);
        assertEquals("RELEASED",row.get("logical_inventory_status"));
        assertEquals("NOT_OCCUPIED",row.get("warehouse_inventory_status"));
    }
    @Test void futureFulfillmentStageNeedsActualWarehouseData(){
        Map<String,Object> row=new HashMap<>();row.put("status","WAREHOUSE_PROCESSING");row.put("inventory_status","RESERVED");
        OrderInventoryStatus.decorate(row);
        assertEquals("UNKNOWN",row.get("warehouse_inventory_status"));
    }
}
