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
 * 采购单主表
 * </p>
 *
 * @author 月光光
 * @since 2023-07-20
 */
@TableName("po_info")
@Data
public class PoInfo implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 采购单号
     */
    private String poSn;

    /**
     * 采购单名称
     */
    private String poName;

    /**
     * 1 新建 2 待收货 3 收货中 4 已完结 -1 已作废
     */
    private Byte poState;

    /**
     * 供应商编码
     */
    private String supplierSn;

    /**
     * 采购部门
     */
    private String departmentCode;

    /**
     * 入库仓库编码
     */
    private String wmsSimulationCode;

    /**
     * 计划入库数量
     */
    private Integer numberExpected;

    /**
     * 实际入库数量
     */
    private Integer numberActually;

    /**
     * 计划入库差异（不落库处理，用number_expected-number_actually）实时计算
     */
    private Integer numberDifference;
    private String perationUser;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * 备注
     */
    private String remarks;

    /**
     * 最近入库时间
     */
    private Date wmsUpdatedAt;

    /**
     * 计划入库货值
     */
    private BigDecimal moneyExpected;

    /**
     * 实际入库货值
     */
    private BigDecimal moneyActually;

    /**
     * 来源
     */
    private String comeFrom;

    /**
     * 1 真实出库 2 虚拟出库
     */
    private Byte actualWarehouse;
}
