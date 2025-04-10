package com.oms.inventory.model.dto.Inventory;

import com.oms.inventory.model.entity.OmsChannelInventory;
import lombok.Data;

@Data
public class OmsChannelInventoryDto {
    private OmsChannelInventory omsChannelInventory;
    private String methodName;
    private String relatedId;
}
