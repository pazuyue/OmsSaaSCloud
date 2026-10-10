package com.oms.goods.model.vo.export;

import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.Max;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = false)
public class GoodsVO {
    /** Original Excel row, including blank rows; not a template column. */
    private Integer sourceRowNum;
    /** Numeric source cells that must not be silently truncated/defaulted by Excel converters. */
    private String sourceErrors;
    @Excel(name = "SKU(公司内唯一，≤30位，不含空白，必填)", width = 20)
    @NotBlank(message = "SKU不能为空")
    private String skuSn;
    @Excel(name = "条形码(公司内唯一，英文数字或-_，≤30位，选填)", width = 20)
    private String barcodeSn;
    @Excel(name = "货号(可多SKU共用，≤30位，不含空白，必填)", width = 20)
    @NotBlank(message = "货号不能为空")
    private String goodsSn;
    @Excel(name = "商品名称(≤100字符，不限格式，必填)", width = 20)
    @NotBlank(message = "商品名称不能为空")
    private String goodsName;
    @Excel(name = "分类(三级分类完整路径，以/分隔，必填)", width = 35)
    @NotBlank(message = "分类不能为空")
    private String categoryName;
    @Excel(name = "颜色(≤30字符，不限格式，必填)", width = 20)
    @NotBlank(message = "颜色不能为空")
    private String colorName;
    @Excel(name = "尺码(≤30字符，不限格式，必填)", width = 20)
    @NotBlank(message = "尺码不能为空")
    private String sizeName;
    @Excel(name = "市场价(吊牌价，≥0，必填)", width = 20)
    @NotNull(message = "市场价不能为空")
    @PositiveOrZero(message = "市场价不能为负数")
    private BigDecimal marketPrice;
    @Excel(name = "有效期(填写整数，需≥0，单位：天)", width = 20)
    private Integer validity;
    @Excel(name = "商品描述(非必填)", width = 20)
    private String goodsDesc;
    @Excel(name = "是否福袋(0否/1是，选填)", width = 20)
    @PositiveOrZero
    @Max(1)
    private Integer isFd;
    @Excel(name = "是否赠品(0否/1是，选填)", width = 20)
    @PositiveOrZero
    @Max(1)
    private Integer isGift;

    @Excel(name = "是否套装(0否/1是，选填)", width = 20)
    @PositiveOrZero
    @Max(1)
    private Integer isPackage;

}
