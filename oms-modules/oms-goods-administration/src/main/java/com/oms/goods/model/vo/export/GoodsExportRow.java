package com.oms.goods.model.vo.export;
import lombok.Data;
import com.ruoyi.common.core.annotation.Excel;
@Data
public class GoodsExportRow {
    @Excel(name="SKU") private String skuSn;
    @Excel(name="货号") private String goodsSn;
    @Excel(name="条形码") private String barcodeSn;
    @Excel(name="商品名称") private String goodsName;
    @Excel(name="分类路径") private String categoryPath;
    @Excel(name="颜色") private String colorName;
    @Excel(name="尺码") private String sizeName;
    @Excel(name="市场价") private java.math.BigDecimal marketPrice;
    @Excel(name="保质期（天）") private String validity;
    @Excel(name="商品描述") private String goodsDesc;
    @Excel(name="是否福袋",readConverterExp="0=否,1=是") private Integer isFd;
    @Excel(name="是否赠品",readConverterExp="0=否,1=是") private Integer isGift;
    @Excel(name="是否套装",readConverterExp="0=否,1=是") private Integer isPackage;
    @Excel(name="备注") private String description;
}
