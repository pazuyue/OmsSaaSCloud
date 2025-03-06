package com.oms.inventory.model.dto;

import com.oms.inventory.model.entity.WmsInventoryBatch;
import lombok.Data;

@Data
public class WmsInventoryBatchDto {
    private WmsInventoryBatch wmsInventoryBatch;
    private String relationSn; // 关联单号
}
