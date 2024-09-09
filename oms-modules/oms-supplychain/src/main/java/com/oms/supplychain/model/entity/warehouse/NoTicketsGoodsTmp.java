package com.oms.supplychain.model.entity.warehouse;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;

/**
 * 入库通知单明细-未送审对象 no_tickets_goods_tmp
 *
 * @author ruoyi
 * @date 2024-09-03
 */
@TableName("no_tickets_goods_tmp")
@Data
public class NoTicketsGoodsTmp implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 入库单号
     */
    @Excel(name = "入库单号")
    private String noSn;

    /**
     * sku_sn
     */
    @Excel(name = "sku_sn")
    private String skuSn;

    /**
     * 条形码
     */
    @Excel(name = "条形码")
    private String barcodeSn;

    /**
     * 货号
     */
    @Excel(name = "货号")
    private String goodsSn;

    /**
     * 产品名称
     */
    @Excel(name = "产品名称")
    private String goodsName;

    /**
     * 采购价格
     */
    @Excel(name = "采购价格")
    private BigDecimal purchasePrice;

    /**
     * 计划入库-正品数量
     */
    @Excel(name = "计划入库-正品数量")
    private Integer zpNumberExpected;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * 错误信息
     */
    @Excel(name = "错误信息")
    private String errorInfo;

    /**
     * 修改时间
     */
    private Date modifyTime;
}

