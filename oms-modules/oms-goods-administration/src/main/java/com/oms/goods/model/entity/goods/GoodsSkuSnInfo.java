package com.oms.goods.model.entity.goods;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * <p>
 * 产品信息表
 * </p>
 *
 * @author 月光
 * @since 2024-07-30
 */
@TableName("goods_sku_sn_info")
public class GoodsSkuSnInfo implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * sku
     */
    private String skuSn;

    /**
     * 货号
     */
    private String goodsSn;

    /**
     * 条形码
     */
    private String barcodeSn;

    /**
     * 商品名称
     */
    private String goodsName;

    /**
     * 类目code
     */
    private Integer categoryCode;

    /**
     * 颜色编码
     */
    private Integer colorCode;

    /**
     * 尺码,goods_size.id
     */
    private Integer sizeCode;

    /**
     * 市场价（吊牌价）
     */
    private BigDecimal marketPrice;

    /**
     * 有效期
     */
    private String validity;

    /**
     * 商品描述
     */
    private String goodsDesc;

    /**
     * 是否福袋,0 不是福袋，1是福袋
     */
    private Byte isFd;

    /**
     * 0为非赠口，1为赠品
     */
    private Byte isGift;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * 添加时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 0正常商品，1套装商品
     */
    private Byte isPackage;

    /**
     * 创建套装的用户编码
     */
    private String createPackageUser;

    /**
     * 商品描述
     */
    private String description;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSkuSn() {
        return skuSn;
    }

    public void setSkuSn(String skuSn) {
        this.skuSn = skuSn;
    }

    public String getGoodsSn() {
        return goodsSn;
    }

    public void setGoodsSn(String goodsSn) {
        this.goodsSn = goodsSn;
    }

    public String getBarcodeSn() {
        return barcodeSn;
    }

    public void setBarcodeSn(String barcodeSn) {
        this.barcodeSn = barcodeSn;
    }

    public String getGoodsName() {
        return goodsName;
    }

    public void setGoodsName(String goodsName) {
        this.goodsName = goodsName;
    }

    public Integer getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    public Integer getColorCode() {
        return colorCode;
    }

    public void setColorCode(Integer colorCode) {
        this.colorCode = colorCode;
    }

    public Integer getSizeCode() {
        return sizeCode;
    }

    public void setSizeCode(Integer sizeCode) {
        this.sizeCode = sizeCode;
    }

    public BigDecimal getMarketPrice() {
        return marketPrice;
    }

    public void setMarketPrice(BigDecimal marketPrice) {
        this.marketPrice = marketPrice;
    }

    public String getValidity() {
        return validity;
    }

    public void setValidity(String validity) {
        this.validity = validity;
    }

    public String getGoodsDesc() {
        return goodsDesc;
    }

    public void setGoodsDesc(String goodsDesc) {
        this.goodsDesc = goodsDesc;
    }

    public Byte getIsFd() {
        return isFd;
    }

    public void setIsFd(Byte isFd) {
        this.isFd = isFd;
    }

    public Byte getIsGift() {
        return isGift;
    }

    public void setIsGift(Byte isGift) {
        this.isGift = isGift;
    }

    public String getCompanyCode() {
        return companyCode;
    }

    public void setCompanyCode(String companyCode) {
        this.companyCode = companyCode;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getModifyTime() {
        return modifyTime;
    }

    public void setModifyTime(Date modifyTime) {
        this.modifyTime = modifyTime;
    }

    public Byte getIsPackage() {
        return isPackage;
    }

    public void setIsPackage(Byte isPackage) {
        this.isPackage = isPackage;
    }

    public String getCreatePackageUser() {
        return createPackageUser;
    }

    public void setCreatePackageUser(String createPackageUser) {
        this.createPackageUser = createPackageUser;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "GoodsSkuSnInfo{" +
        ", id = " + id +
        ", skuSn = " + skuSn +
        ", goodsSn = " + goodsSn +
        ", barcodeSn = " + barcodeSn +
        ", goodsName = " + goodsName +
        ", categoryCode = " + categoryCode +
        ", colorCode = " + colorCode +
        ", sizeCode = " + sizeCode +
        ", marketPrice = " + marketPrice +
        ", validity = " + validity +
        ", goodsDesc = " + goodsDesc +
        ", isFd = " + isFd +
        ", isGift = " + isGift +
        ", companyCode = " + companyCode +
        ", createTime = " + createTime +
        ", modifyTime = " + modifyTime +
        ", isPackage = " + isPackage +
        ", createPackageUser = " + createPackageUser +
        ", description = " + description +
        "}";
    }
}
