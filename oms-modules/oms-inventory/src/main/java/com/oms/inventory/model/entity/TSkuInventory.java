package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 商品库存表维度：sku_sn + company_code
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@Data
@TableName("t_sku_inventory")
public class TSkuInventory implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * sku
     */
    private String skuSn;

    /**
     * 正品库存：实际库存发货时减少
     */
    private Integer zpActualNumber;

    /**
     * 次品库存：实际库存发货时减少
     */
    private Integer cpActualNumber;

    /**
     * 正品可用库存
     */
    private Integer zpAvailableNumber;

    /**
     * 次品可用库存
     */
    private Integer cpAvailableNumber;

    /**
     * 总预占库存：正品(退仓，B2B出库，调拨出库，其他出库等：所有单据的库存预占) ,包括套装的预占
     */
    private Integer zpLockNumber;

    /**
     * 正品预占库存
     */
    private Integer cpLockNumber;

    /**
     * 备注
     */
    private String remark;

    /**
     * 品牌编码
     */
    private String brandCode;

    /**
     * 修改时间
     */
    private Date modifyTime;
    private Date createTime;

    private String companyCode;
}
