package com.oms.goods.model.vo.export;

import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = false)
public class GoodsVO {
    @Excel(name = "SKU(系统唯一，≤30位，可英文数字符号（除空格外），必填)", width = 20)
    @NotBlank(message = "SKU不能为空")
    private String skuSn;
    @Excel(name = "条形码(系统唯一，可支持英文数字、“-”、“_”，≤30位数字，必填)", width = 20)
    @NotBlank(message = "条形码不能为空")
    private String barcodeSn;
    @Excel(name = "货号(系统唯一，≤30位，可英文数字符号（除空格外），必填)", width = 20)
    @NotBlank(message = "货号不能为空")
    private String goodsSn;
    @Excel(name = "商品名称(≤100字符，不限格式，必填)", width = 20)
    @NotBlank(message = "商品名称不能为空")
    private String goodsName;
    @Excel(name = "分类(第三级分类名称，必填)", width = 20)
    @NotBlank(message = "分类不能为空")
    private String categoryName;
    @Excel(name = "颜色(≤30字符，不限格式，必填)", width = 20)
    @NotBlank(message = "颜色不能为空")
    private String colorName;
    @Excel(name = "尺码(≤30字符，不限格式，必填)", width = 20)
    @NotBlank(message = "尺码不能为空")
    private String sizeName;
    @Excel(name = "市场价(吊牌价，≥0，必填)", width = 20)
    @Positive(message = "市场价不能为空")
    private BigDecimal marketPrice;
    @Excel(name = "有效期(填写整数，需≥0，单位：天)", width = 20)
    private Integer validity;
    @Excel(name = "商品描述(非必填)", width = 20)
    private String description;
}
