package com.oms.inventory.model.vo;

import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RuleStockExcel {
    @Excel(name = "SKU(必填)", width = 20)
    @NotBlank(message = "SKU不能为空")
    private String skuSn;
}
