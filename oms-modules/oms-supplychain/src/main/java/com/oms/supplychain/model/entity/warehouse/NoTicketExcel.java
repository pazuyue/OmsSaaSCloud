package com.oms.supplychain.model.entity.warehouse;

import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
public class NoTicketExcel {
    @Excel(name = "SKU(必填)", width = 20)
    @NotBlank(message = "SKU不能为空")
    private String skuSn;

    @Excel(name = "采购价(必填)", width = 20)
    @Positive(message = "采购价不能为空")
    private BigDecimal purchasePrice;

    @Excel(name = "计划入库数量(必填)", width = 20)
    @Positive(message = "计划入库数量不能为空")
    private Integer numberExpected;
}
