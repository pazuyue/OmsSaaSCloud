package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * <p>
 * 仓库库存表-批次维度：sku_sn + company_code + batch_code + store_code
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@TableName("wms_inventory_batch")
@Data
public class WmsInventoryBatch implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 仓库编码
     */
    private String storeCode;

    /**
     * sku
     */
    private String skuSn;

    /**
     * 正品库存：实际库存发货时减少
     */
    private Integer zpActualNumber = 0;

    /**
     * 次品库存：实际库存发货时减少
     */
    private Integer cpActualNumber = 0;

    /**
     * 正品可用库存
     */
    private Integer zpAvailableNumber = 0;

    /**
     * 次品可用库存
     */
    private Integer cpAvailableNumber = 0;

    /**
     * 正品预占库存(总)
     */
    private Integer zpLockNumber = 0;

    /**
     * 正品预占库存
     */
    private Integer cpLockNumber = 0;

    /**
     * 备注
     */
    private String remark;

    /**
     * 采购批次
     */
    private String batchCode;

    /**
     * 批次的成本价
     */
    private BigDecimal transactionPrice = new BigDecimal(0.00);

    /**
     * 品牌编码
     */
    private String brandCode;
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;
    private String companyCode;
}
