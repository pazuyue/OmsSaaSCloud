package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * WMS库存变动历史记录表
 * 用于记录仓库库存的变动历史
 *
 * @author AI Assistant
 * @since 2024-01-01
 */
@Data
@TableName("wms_inventory_change_history")
public class WmsInventoryChangeHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 日志唯一标识
     */
    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;

    /**
     * 操作类型（LOCK/UNLOCK/ADJUST/OTHER）
     */
    private String operationType;

    /**
     * 仓库编码
     */
    private String storeCode;

    /**
     * SKU编号
     */
    private String skuSn;

    /**
     * 变更前正品实际库存
     */
    private Integer oldZpActualNumber;

    /**
     * 变更后正品实际库存
     */
    private Integer newZpActualNumber;

    /**
     * 变更前正品可用库存
     */
    private Integer oldZpAvailableNumber;

    /**
     * 变更后正品可用库存
     */
    private Integer newZpAvailableNumber;

    /**
     * 变更前正品锁定库存
     */
    private Integer oldZpLockNumber;

    /**
     * 变更后正品锁定库存
     */
    private Integer newZpLockNumber;

    /**
     * 变更前次品实际库存
     */
    private Integer oldCpActualNumber;

    /**
     * 变更后次品实际库存
     */
    private Integer newCpActualNumber;

    /**
     * 变更前次品可用库存
     */
    private Integer oldCpAvailableNumber;

    /**
     * 变更后次品可用库存
     */
    private Integer newCpAvailableNumber;

    /**
     * 变更前次品锁定库存
     */
    private Integer oldCpLockNumber;

    /**
     * 变更后次品锁定库存
     */
    private Integer newCpLockNumber;

    /**
     * 变动数量
     */
    private BigDecimal changeQuantity;

    /**
     * 变动原因（如锁库分货、解锁库存、库存调整）
     */
    private String changeReason;

    /**
     * 关联单号（如规则ID、订单号等）
     */
    private String relationSn;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作人名称
     */
    private String operatorName;

    /**
     * 操作时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * 备注
     */
    private String remark;

    /**
     * 数据创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 数据更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime modifyTime;
}