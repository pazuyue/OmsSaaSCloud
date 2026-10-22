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
    @Excel(name="商品形态",readConverterExp="NORMAL=普通商品,BUNDLE=固定套装,LUCKY_BAG=福袋") private String productType;
    @Excel(name="允许作为赠品",readConverterExp="0=否,1=是") private Integer isGift;
    @Excel(name="备注") private String description;
}
