package com.oms.supplychain.model.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 实仓表
 * </p>
 *
 * @author 月光光
 * @since 2023-07-18
 */
@TableName("wms_real_store_info")
@Data
public class WmsRealStoreInfo implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 启用状态,1没有启动，2启动
     */
    private Byte status;

    /**
     * 1:电商仓，2：门店仓，3：零售仓
     */
    private Byte wmsType;

    /**
     * 实仓编码
     */
    private String realStoreCode;

    /**
     * 实仓名称
     */
    private String wmsName;

    /**
     * 仓库负责人
     */
    private String director;

    /**
     * 联系电话
     */
    private String mobilePhone;

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
     * 地址
     */
    private String address;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 1 真实出库 2 虚拟出库
     */
    private Byte actualWarehouse;

    /**
     * 货主
     */
    private String ownerCode;
}
