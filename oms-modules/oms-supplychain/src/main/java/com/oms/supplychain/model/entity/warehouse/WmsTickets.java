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
 * 出入库单
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
@TableName("wms_tickets")
@Data
public class WmsTickets implements Serializable {

    private static final long serialVersionUID = 1L;

    /**  */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 单据编号 */
    @Excel(name = "单据编号")
    private String sn;

    /** 出入库类型 1：入库 2：出库 */
    @Excel(name = "出入库类型 1：入库 2：出库")
    private Integer ticketType;

    /** 关联单据号 */
    @Excel(name = "关联单据号")
    private String relationSn;

    /** 源单号 */
    @Excel(name = "源单号")
    private String originalSn;

    /** 指派的虚仓编码 */
    @Excel(name = "指派的虚仓编码")
    private String wmsSimulationCode;

    /** 指派的虚仓名称 */
    @Excel(name = "指派的虚仓名称")
    private String wmsSimulationName;

    /** 1电商仓，2门店（未实现） */
    @Excel(name = "1电商仓，2门店", readConverterExp = "未=实现")
    private Integer storeType;

    /** 单据状态，0：待确认，1：已确认待处理，2：已处理完成，3：处理失败，4：待废弃，5：已废弃完成，6：废弃失败  */
    @Excel(name = "单据状态，0：待确认，1：已确认待处理，2：已处理完成，3：处理失败，4：待废弃，5：已废弃完成，6：废弃失败 ")
    private Integer statusTicket;

    /** 通知状态， 0：待通知，1：通知成功，2：通知失败 3.已通知待确认 */
    @Excel(name = "通知状态， 0：待通知，1：通知成功，2：通知失败 3.已通知待确认")
    private Integer statusNotify;

    /** 查询状态，0：全部待查询，1：全部查询成功，2：部分查询成功，3：全部查询失败 */
    @Excel(name = "查询状态，0：全部待查询，1：全部查询成功，2：部分查询成功，3：全部查询失败")
    private Integer statusQuery;

    /** 通知成功时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "通知成功时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date timeNotify;

    /** 收到反馈完成出入库时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "收到反馈完成出入库时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date timeQuery;

    /** 作废成功时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "作废成功时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date timeCancel;

    /** 实际出库时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "实际出库时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date actuallyOutTime;

    /** 快递名称 */
    @Excel(name = "快递名称")
    private String shippingName;

    /** 快递编码 */
    @Excel(name = "快递编码")
    private String shippingCode;

    /** 最终发货仓库编码(实仓) */
    @Excel(name = "最终发货仓库编码(实仓)")
    private String realStoreCode;

    /** 修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "修改时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date modifyTime;

    /** wms的出入库时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "wms的出入库时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date wmsActuallyTime;

    /** 创建人的用户名称 */
    @Excel(name = "创建人的用户名称")
    private String userName;

    /** 客户编码（仓库需要） */
    @Excel(name = "客户编码", readConverterExp = "仓=库需要")
    private String customerNo;

    @Excel(name = "备注")
    private String remark;

    /** WMS仓库接单时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "WMS仓库接单时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date acceptTime;

    /** WMS仓库接单回调到OMS时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "WMS仓库接单回调到OMS时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date acceptCallbackTime;

    /** 1 真实出库 2 虚拟出库  */
    @Excel(name = "1 真实出库 2 虚拟出库 ")
    private Integer actualWarehouse;

    /** 公司编码 */
    @Excel(name = "公司编码")
    private String companyCode;

}
