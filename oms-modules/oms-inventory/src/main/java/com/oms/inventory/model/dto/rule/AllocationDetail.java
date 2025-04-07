package com.oms.inventory.model.dto.rule;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@AllArgsConstructor
@Data
public class AllocationDetail {
    private Integer channelId;
    private String warehouseCode;
    private BigDecimal allocatedAmount;
}
