package com.ruoyi.goods.model.entity.goods;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * <p>
 * 产品信息临时表
 * </p>
 *
 * @author 月光光
 * @since 2023-07-11
 */
@TableName("goods_sku_sn_info_tmp")
@Data
public class GoodsSkuSnInfoTmp implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String importBatch;
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
    private String categoryCode;

    /**
     * 颜色编码
     */
    private String colorCode;

    /**
     * 尺码
     */
    private String sizeCode;

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
     * 是否福袋,
     */
    private Byte isFd;

    /**
     * 是否赠品
     */
    private Byte isGift;

    /**
     * 批次号
     */
    private String batchId;

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
     * 导入说明
     */
    private String notes;

}
