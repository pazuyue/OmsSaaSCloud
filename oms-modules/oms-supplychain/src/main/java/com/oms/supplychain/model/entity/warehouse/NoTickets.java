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
 * 采购入库通知单
 * </p>
 *
 * @author 月光光
 * @since 2023-07-21
 */
@TableName("no_tickets")
@Data
public class NoTickets implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 入库单号
     */
    private String noSn;

    /**
     * 关联采购单号
     */
    private String poSn;

    /**
     * 关联单号（关联入库单号 CG 开头）
     */
    private String relationSn;

    /**
     * 入库单名称
     */
    private String noName;

    /**
     * 入库虚仓仓库编码
     */
    private String wmsSimulationCode;
    /**
     * 批次编号
     */
    private String batchCode;

    /**
     * 计划到货时间
     */
    private Date expectedCallbackTime;

    /**
     * 实际入库时间
     */
    private Date actuallyCallbackTime;

    /**
     * -1 已作废 1 新建，2 待审核 3 待入库 4 已入库
     */
    private Integer noState;

    /**
     * 备注
     */
    private String remarks;

    /**
     * 计划入库数量
     */
    private Integer numberExpected;

    /**
     * 实际入库数量
     */
    private Integer numberActually;


    /**
     * 计划入库货值
     */
    private BigDecimal priceExpected;

    /**
     * 实际入库货值
     */
    private BigDecimal priceActually;

    /**
     * 公司编码
     */
    private String companyCode;
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 创建者
     */
    private String createdUser;

    /**
     * 审核者
     */
    private String reviewerUser;

    /**
     * 审核时间
     */
    private Date reviewerTime;

    /**
     * 来源
     */
    private String comeFrom;

    /**
     * 0正常流程，1收货申请单
     */
    private Byte comeFromType;
}
