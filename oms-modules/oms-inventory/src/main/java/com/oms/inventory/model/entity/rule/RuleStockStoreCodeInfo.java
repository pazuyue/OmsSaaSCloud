package com.oms.inventory.model.entity.rule;

import java.io.Serializable;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;

/**
 * 【请填写功能名称】对象 rule_stock_store_code_info
 *
 * @author ruoyi
 * @date 2025-03-14
 */
@Data
public class RuleStockStoreCodeInfo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 规则ID rule_stock_info.id */
    @Excel(name = "规则ID rule_stock_info.id")
    private Integer ruleId;

    /** 虚仓编码wms_simulation_store_info.wms_simulation_code */
    @Excel(name = "虚仓编码wms_simulation_store_info.wms_simulation_code")
    private String storeCode;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private LocalDateTime modifyTime;

    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Excel(name = "创建", width = 30, dateFormat = "yyyy-MM-dd")
    private LocalDateTime createTime;

    /** $column.columnComment */
    @Excel(name = "公司")
    private String companyCode;
}

