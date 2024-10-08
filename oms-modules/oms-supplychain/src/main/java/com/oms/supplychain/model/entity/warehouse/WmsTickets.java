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
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 单据编号
     */
    private String sn;

    /**
     * 出入库类型，0：出库，1：入库
     */
    private Integer ticketType;

    /**
     * 关联单据号
     */
    private String relationSn;

    /**
     * 源单号
     */
    private String originalSn;

    /**
     * 指派的仓库编码
     */
    private String wmsSimulationCode;

    /**
     * 指派的仓库名称
     */
    private String wmsSimulationName;

    /**
     * 1电商仓，2门店
     */
    private Integer storeType;

    /**
     * 配送方式代码(批派到WMS)
     */
    private String shippingCode;

    /**
     * 配送方式名称
     */
    private String shippingName;

    /**
     * 快递单号
     */
    private String invoiceSn;

    /**
     * 收货人姓名
     */
    private String receiverName;

    /**
     * 收货人地址
     */
    private String receiverAddress;

    /**
     * 收货人手机
     */
    private String receiverMobile;

    /**
     * 收货人电话
     */
    private String receiverTelephone;

    /**
     * 收货人邮编
     */
    private String receiverZipcode;

    /**
     * 省
     */
    private String province;

    /**
     * 市
     */
    private String city;

    /**
     * 区
     */
    private String district;

    /**
     * 实际重量
     */
    private BigDecimal actuallyWeight;

    /**
     * 实际运费
     */
    private BigDecimal actuallyShippingFee;

    /**
     * 结算数量
     */
    private Integer numberSettlement;

    /**
     * 单据状态，0：待确认，1：已确认待处理，2：已处理完成，3：处理失败，4：待废弃，5：已废弃完成，6：废弃失败
     */
    private Integer statusTicket;

    /**
     * 1:ACCEPT=仓库接单;  2：PRINT = 打印; 3：PICK=拣货; 4:CHECK = 复核; 5:PACKAGE= 打包；6：WEIGH= 称重; 7：READY=待提货;
     */
    private Byte processStatus;

    /**
     * 8:DELIVERED=已发货;9:DELIVERY_TIMEOUT=发货超时;10:PICK_TIMEOUT=拣货超时;11:PACKAGE_COLLECT=包裹揽收;12:PACKAGE_LOST=包裹丢失;13:PACKAGE_FOUND=包裹找回;14:DDELIVERY=客户签收;15:FDELIVERY=客户拒收
     */
    private Byte inAcShopStatus;

    /**
     * 通知状态， 0：待通知，1：通知成功，2：通知失败 3.已通知待确认
     */
    private Integer statusNotify;

    /**
     * 查询状态，0：全部待查询，1：全部查询成功，2：部分查询成功，3：全部查询失败
     */
    private Integer statusQuery;

    /**
     * 通知重试次数
     */
    private Short retryNotifyCount;

    /**
     * 查询重试次数
     */
    private Short retryQueryCount;

    /**
     * 处理尝试次数
     */
    private Short retryProcessCount;

    /**
     * 通知成功时间
     */
    private Date timeNotify;

    /**
     * 收到反馈完成出入库时间
     */
    private Date timeQuery;

    /**
     * 作废成功时间
     */
    private Date timeCancel;

    /**
     * 实际出库时间
     */
    private Date actuallyOutTime;

    /**
     * 备注
     */
    private String remark;

    /**
     * 最终发货仓库编码(实仓)
     */
    private String realStoreCode;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * wms的出入库时间
     */
    private Date wmsActuallyTime;
    private Date deletedAt;

    /**
     * 创建人的用户名称
     */
    private String userName;

    /**
     * 客户编码（仓库需要）
     */
    private String customerNo;

    /**
     * 门店发货（也就是store_type = 2）,填写真实发货门店编码
     */
    private String shippingStoreCode;

    /**
     * WMS仓库接单时间
     */
    private Date acceptTime;

    /**
     * WMS仓库接单回调到OMS时间
     */
    private Date acceptCallbackTime;

    private Integer actualWarehouse;
}
