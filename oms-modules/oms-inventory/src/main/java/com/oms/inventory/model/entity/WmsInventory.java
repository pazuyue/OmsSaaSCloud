package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 仓库库存表维度：sku_sn + store_code
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@TableName("wms_inventory")
@Data
public class WmsInventory implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 仓库编码
     */
    @Excel(name = "虚仓编码")
    private String storeCode;

    /**
     * sku
     */
    @Excel(name = "sku")
    private String skuSn;

    /**
     * 正品库存：实际库存发货时减少
     */
    @Excel(name = "正品库存")
    private Integer zpActualNumber;

    /**
     * 次品库存：实际库存发货时减少
     */
    @Excel(name = "次品库存")
    private Integer cpActualNumber;

    /**
     * 可用库存-正品
     */
    @Excel(name = "可用正品库存")
    private Integer zpAvailableNumber;

    /**
     * 可用库存-次品
     */
    @Excel(name = "可用次品库存")
    private Integer cpAvailableNumber;


    /**
     * 总正品预占库存,（退仓，B2B出库，其他出库，调拨出库,B2C订单），包装套装预占
     */
    @Excel(name = "正品预占库存")
    private Integer zpLockNumber;

    /**
     * 次品预占库存
     */
    @Excel(name = "次品预占库存")
    private Integer cpLockNumber;

    /**
     * 备注
     */
    @Excel(name = "备注")
    private String remark;

    /**
     * 修改时间
     */
    @Excel(name = "修改时间")
    private Date modifyTime;
    @Excel(name = "修改时间")
    private Date createTime;
    private String companyCode;
}
