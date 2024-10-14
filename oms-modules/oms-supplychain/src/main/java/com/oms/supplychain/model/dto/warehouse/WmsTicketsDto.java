package com.oms.supplychain.model.dto.warehouse;


import com.oms.supplychain.model.entity.warehouse.WmsTicketsGoods;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * 出入库单
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
@Data
public class WmsTicketsDto implements Serializable {

    private static final long serialVersionUID = 1L;
    private Integer id;
    private String sn;
    private Integer ticketType;
    private String relationSn;
    private String originalSn;
    private String wmsSimulationCode;
    private String wmsSimulationName;
    private Integer storeType;
    private String shippingCode;
    private String shippingName;
    private String invoiceSn;
    private String receiverName;
    private String receiverAddress;
    private String receiverMobile;
    private String receiverTelephone;
    private String receiverZipcode;
    private String province;
    private String city;
    private String district;
    private BigDecimal actuallyWeight;
    private BigDecimal actuallyShippingFee;
    private Integer numberSettlement;
    private Integer statusTicket;
    private Byte processStatus;
    private Byte inAcShopStatus;
    private Integer statusNotify;
    private Integer statusQuery;
    private Short retryNotifyCount;
    private Short retryQueryCount;
    private Short retryProcessCount;
    private Date timeNotify;
    private Date timeQuery;
    private Date timeCancel;
    private Date actuallyOutTime;
    private String remark;
    private String realStoreCode;
    private Date modifyTime;
    private Date createTime;
    private Date wmsActuallyTime;
    private Date deletedAt;
    private String userName;
    private String customerNo;
    private String shippingStoreCode;
    private Date acceptTime;
    private Date acceptCallbackTime;
    private Byte actualWarehouse;
    private List<WmsTicketsGoods> wmsTicketsGoodsList;
}
