package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.oms.inventory.model.enums.RuleStatus;
import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分货单基础信息对象 rule_stock_info
 *
 * @author ruoyi
 * @date 2024-12-13
 */
@TableName("rule_stock_info")
@Data
public class RuleStockInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 是否启用
     */
    @Excel(name = "是否启用")
    private Integer enable;

    /**
     * 分货单规则：1，日常分货；2，一次性分货；3，锁库时分货
     */
    @Excel(name = "分货单规则：1，日常分货；2，一次性分货；3，锁库时分货")
    private Integer ruleType;

    /**
     * 分货单编号(系统唯一)
     */
    @Excel(name = "分货单编号(系统唯一)")
    private String ruleCode;

    /**
     * 分货单名称
     */
    @Excel(name = "分货单名称")
    private String ruleName;

    /**
     * 状态
     */
    @Excel(name = "状态")
    private RuleStatus status;

    /**
     * 分货单有效期, 开始时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "分货单有效期, 开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime startTime;

    /**
     * 分货单有效期, 结束时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "分货单有效期, 结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime endTime;

    /**
     * 分货范围 : 1,全部商品; 2部分商品,
     */
    @Excel(name = "分货范围 : 1,全部商品; 2部分商品")
    private Integer ruleRange;

    @Excel(name = "备注信息")
    private String remark;

    /**
     * 商品类型
     */
    @Excel(name = "商品类型")
    private Integer type;

    /**
     * 最新分货时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "最新分货时间", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime lastUpdateTime;

    /**
     * 首次审核时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "首次审核时间", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime firstReviewerTime;

    /**
     * 审核时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "审核时间", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime reviewerTime;

    /**
     * 结束时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime overTime;

    /**
     * 作废时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "作废时间", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime cancelTime;

    /**
     * 审核人姓名
     */
    @Excel(name = "审核人姓名")
    private String reviewerUserName;

    /**
     * 创建人姓名
     */
    @Excel(name = "创建人姓名")
    private String createUserName;

    /**
     * 修改时间
     */
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "修改时间", width = 30, dateFormat = "yyyy-MM-dd")
    @TableField(value = "modify_time",fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime modifyTime;

    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "创建", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime createTime;

    /**
     * $column.columnComment
     */
    private String companyCode;
}
