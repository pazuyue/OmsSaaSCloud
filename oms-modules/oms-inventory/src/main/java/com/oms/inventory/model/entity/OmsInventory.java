package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName("oms_inventory")
@Data
public class OmsInventory implements Serializable {
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Integer id;

    /** SKU编码（唯一标识商品规格） */
    @Excel(name = "SKU编码", readConverterExp = "唯=一标识商品规格")
    private String skuSn;

    /** 总库存（逻辑库存，包含所有仓库/渠道库存） */
    @Excel(name = "总库存", readConverterExp = "逻=辑库存，包含所有仓库/渠道库存")
    private Integer totalStock;

    /** 可售库存（扣除预留、冻结、在途库存） */
    @Excel(name = "可售库存", readConverterExp = "扣=除预留、冻结、在途库存")
    private Integer availableStock;

    /** 预留库存（订单占用、活动预占等） */
    @Excel(name = "预留库存", readConverterExp = "订=单占用、活动预占等")
    private Integer reservedStock;

    /** 冻结库存（如预售、退货中库存） */
    @Excel(name = "冻结库存", readConverterExp = "如=预售、退货中库存")
    private Integer frozenStock;

    /** 安全库存阈值 */
    @Excel(name = "安全库存阈值")
    private Integer safetyStock;

    /** 最小库存预警值 */
    @Excel(name = "最小库存预警值")
    private Integer minStock;

    /** 数据更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "数据更新时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date modifyTime;

    @Excel(name = "公司编码")
    private String companyCode;

    /** 乐观锁版本号 */
    @Excel(name = "乐观锁版本号")
    private Integer version;

}
