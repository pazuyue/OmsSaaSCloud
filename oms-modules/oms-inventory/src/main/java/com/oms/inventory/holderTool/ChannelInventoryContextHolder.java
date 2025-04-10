package com.oms.inventory.holderTool;

import com.oms.inventory.model.dto.Inventory.OmsChannelInventoryDto;

public class ChannelInventoryContextHolder {
    private static final ThreadLocal<OmsChannelInventoryDto> CONTEXT_HOLDER = new ThreadLocal<>();

    public static void setBeforeEntity(OmsChannelInventoryDto entity) {
        CONTEXT_HOLDER.set(entity);
    }

    public static OmsChannelInventoryDto getBeforeEntity() {
        return CONTEXT_HOLDER.get();
    }

    public static void clear() {
        CONTEXT_HOLDER.remove();
    }
}
