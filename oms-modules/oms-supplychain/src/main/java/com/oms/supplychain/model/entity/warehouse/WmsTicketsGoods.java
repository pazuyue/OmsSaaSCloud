package com.oms.supplychain.model.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * <p>
 * 出入库明细表表
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
@TableName("wms_tickets_goods")
@Data
public class WmsTicketsGoods implements Serializable {

    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 出入库单sn */
    @Excel(name = "出入库单sn")
    private String sn;

    /** ZP正品，CP次品 */
    @Excel(name = "ZP正品，CP次品")
    private String inventoryType;

    /** 内部sku_sn */
    @Excel(name = "内部sku_sn")
    private String skuSn;

    /** 货号 */
    @Excel(name = "货号")
    private String goodsSn;

    /** 条码 */
    @Excel(name = "条码")
    private String barcodeSn;

    /** 品牌编码 */
    @Excel(name = "品牌编码")
    private String brandCode;

    /** 产品名 */
    @Excel(name = "产品名")
    private String goodsName;

    /** 批次 */
    @Excel(name = "批次")
    private String batchCode;

    /** 采购价 */
    @Excel(name = "采购价")
    private BigDecimal purchasePrice;

    /** 预期出入库数量 */
    @Excel(name = "预期出入库数量")
    private Integer numberExpected;

    /** 实际出入库数量 */
    @Excel(name = "实际出入库数量")
    private Integer numberActually;

    /** 实收良品数量 */
    @Excel(name = "实收良品数量")
    private Integer numberZp;

    /** 实收次品数量 */
    @Excel(name = "实收次品数量")
    private Integer numberCp;

    /** 实际出入库数量的差异 */
    @Excel(name = "实际出入库数量的差异")
    private Integer numberDifActually;

    /** 修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "修改时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date modifyTime;

    /** wms的出入库时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "wms的出入库时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date wmsActuallyTime;

    /** 库存处理结果 0未处理 1处理中 2处理成功 3处理失败 */
    @Excel(name = "库存处理结果 0未处理 1处理中 2处理成功 3处理失败")
    private Integer inventoryIsHandle;

    /** 公司编码 */
    @Excel(name = "公司编码")
    private String companyCode;
}
