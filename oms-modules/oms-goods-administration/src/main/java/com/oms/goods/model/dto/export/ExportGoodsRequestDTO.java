package com.oms.goods.model.dto.export;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ExportGoodsRequestDTO {
    @JsonProperty("import_batch")
    private String importBatch;
    private Integer pageNum;
    private Integer pageSize;
}
