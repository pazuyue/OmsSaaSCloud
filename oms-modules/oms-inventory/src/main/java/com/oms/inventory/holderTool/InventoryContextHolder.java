package com.oms.inventory.holderTool;

import com.oms.inventory.model.entity.OmsInventory;

public class InventoryContextHolder {
    private static final ThreadLocal<OmsInventory> CONTEXT_HOLDER = new ThreadLocal<>();

    public static void setBeforeEntity(OmsInventory entity) {
        CONTEXT_HOLDER.set(entity);
    }

    public static OmsInventory getBeforeEntity() {
        return CONTEXT_HOLDER.get();
    }

    public static void clear() {
        CONTEXT_HOLDER.remove();
    }
}
