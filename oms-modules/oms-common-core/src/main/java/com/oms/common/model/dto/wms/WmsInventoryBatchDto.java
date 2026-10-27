package com.oms.common.model.dto.wms;
import com.oms.common.model.entity.wms.WmsInventoryBatch;
import lombok.Data;

@Data
public class WmsInventoryBatchDto {
    private WmsInventoryBatch wmsInventoryBatch;
    private String relationSn; // 关联单号
    private boolean purchaseQuality; // 仅采购入库显式启用次品不可售口径，其他入库沿用原约定
}
