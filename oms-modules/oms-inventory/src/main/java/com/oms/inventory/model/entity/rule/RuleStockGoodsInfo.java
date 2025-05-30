package com.oms.inventory.model.entity.rule;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

@Data
@TableName("rule_stock_goods_info")
public class RuleStockGoodsInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 规则ID rule_stock_info.id */
    @Excel(name = "规则ID")
    private Integer ruleId;

    /** 规则ID sku_sn */
    @Excel(name = "sku_sn")
    private String skuSn;

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
