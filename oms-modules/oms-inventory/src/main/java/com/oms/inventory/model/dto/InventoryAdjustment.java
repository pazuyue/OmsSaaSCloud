package com.oms.inventory.model.dto;

import lombok.Data;
import javax.validation.constraints.*;

@Data
public class InventoryAdjustment {
    @NotNull private Long batchId;
    @NotNull private Integer version;
    @NotNull private Integer quantity;
    @NotBlank private String inventoryType;
    @NotBlank @Size(max=255) private String reason;
    @NotBlank @Size(min=16,max=64) private String requestId;
}
