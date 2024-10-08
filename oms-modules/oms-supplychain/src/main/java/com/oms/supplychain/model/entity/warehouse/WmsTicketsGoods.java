package com.oms.supplychain.model.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 出入库单sn
     */
    private String sn;

    /**
     * ZP正品，CP次品
     */
    private String inventoryType;

    /**
     * 内部sku_sn
     */
    private String skuSn;

    /**
     * 货号
     */
    private String goodsSn;

    /**
     * 条码
     */
    private String barcodeSn;

    /**
     * 品牌编码
     */
    private String brandCode;

    /**
     * 产品名
     */
    private String goodsName;

    /**
     * 批次
     */
    private String batchCode;

    /**
     * 采购价
     */
    private BigDecimal purchasePrice;

    /**
     * 预期出入库数量
     */
    private Integer numberExpected;

    /**
     * 实际出入库数量
     */
    private Integer numberActually;

    /**
     * 实收良品数量
     */
    private Integer numberZp;

    /**
     * 实收次品数量
     */
    private Integer numberCp;

    /**
     * 实际出入库数量的差异
     */
    private Integer numberDifActually;

    private Integer inventoryIsHandle;

    /**
     * 修改时间
     */
    private Date modifyTime;
    private Date createTime;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * wms的出入库时间
     */
    private Date wmsActuallyTime;
}
