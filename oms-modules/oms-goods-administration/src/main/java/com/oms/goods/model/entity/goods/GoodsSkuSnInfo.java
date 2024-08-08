package com.oms.goods.model.entity.goods;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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
@Data
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
    private Integer isFd;

    /**
     * 0为非赠口，1为赠品
     */
    private Integer isGift;

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
    private Integer isPackage;

    /**
     * 创建套装的用户编码
     */
    private String createUser;

    /**
     * 商品描述
     */
    private String description;
}
