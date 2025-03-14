package com.oms.inventory.model.entity.rule;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import com.ruoyi.common.core.annotation.Excel;

/**
 * 分货单-渠道分货信息对象 rule_stock_channel_info
 *
 * @author ruoyi
 * @date 2025-03-10
 */
@Data
@TableName("rule_stock_channel_info")
public class RuleStockChannelInfo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 规则ID rule_stock_info.id */
    @Excel(name = "规则ID")
    private Integer ruleId;

    /** 渠道ID */
    @Excel(name = "渠道ID")
    private Integer channelId;

    /** $column.columnComment */
    @Excel(name = "设置值")
    private BigDecimal percentage;

    /** 小数点处理 1向下取整 2向上取整 3四舍五入 */
    @Excel(name = "小数点处理 1向下取整 2向上取整 3四舍五入")
    private Integer decimalHandleType;

    /** $column.columnComment */
    @Excel(name = "修改时间",width = 30, dateFormat = "yyyy-MM-dd")
    private Date modifyTime;

    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "创建", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime createTime;

    /** $column.columnComment */
    @Excel(name = "公司")
    private String companyCode;
}
