package com.oms.common.model.dto.wms;
import com.oms.common.model.entity.wms.WmsInventoryBatch;
import lombok.Data;

@Data
public class WmsInventoryBatchDto {
    private WmsInventoryBatch wmsInventoryBatch;
    private String relationSn; // 关联单号
}
