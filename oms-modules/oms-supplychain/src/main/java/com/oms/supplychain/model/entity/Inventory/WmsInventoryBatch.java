package com.oms.supplychain.model.entity.Inventory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    @TableField(value = "wms_simulation_code")
    private String wmsSimulationCode;

    /**
     * sku
     */
    @TableField(value = "sku_sn")
    private String skuSn;

    /**
     * 正品库存：实际库存发货时减少
     */
    @TableField(value = "zp_actual_number")
    private Integer zpActualNumber = 0;

    /**
     * 次品库存：实际库存发货时减少
     */
    @TableField(value = "cp_actual_number")
    private Integer cpActualNumber = 0;

    /**
     * 正品可用库存
     */
    @TableField(value = "zp_available_number")
    private Integer zpAvailableNumber = 0;

    /**
     * 次品可用库存
     */
    @TableField(value = "cp_available_number")
    private Integer cpAvailableNumber = 0;

    /**
     * 正品预占库存(总)
     */
    @TableField(value = "zp_lock_number")
    private Integer zpLockNumber = 0;

    /**
     * 正品预占库存
     */
    @TableField(value = "cp_lock_number")
    private Integer cpLockNumber = 0;

    /**
     * 备注
     */
    private String remark;

    /**
     * 采购批次
     */
    @TableField(value = "batch_code")
    private String batchCode;

    /**
     * 批次的成本价
     */
    @TableField(value = "transaction_price")
    private BigDecimal transactionPrice = new BigDecimal(0.00);

    /**
     * 品牌编码
     */
    @TableField(value = "brand_code")
    private String brandCode;
    @TableField(value = "create_time")
    private Date createTime;

    /**
     * 修改时间
     */
    @TableField(value = "modify_time")
    private Date modifyTime;
}
