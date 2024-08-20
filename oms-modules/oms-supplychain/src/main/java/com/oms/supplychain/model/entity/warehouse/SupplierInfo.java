package com.oms.supplychain.model.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 供应商主表
 * </p>
 *
 * @author 月光光
 * @since 2023-07-19
 */
@TableName("supplier_info")
@Data
public class SupplierInfo implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 供应商编码
     */
    private String supplierSn;

    /**
     * 供应商简称
     */
    private String supplierName;

    /**
     * 公司全称
     */
    private String companyName;

    /**
     * 联系人
     */
    private String contactUser;

    /**
     * 联系电话
     */
    private String contactTel;

    /**
     * 省
     */
    private String contactProvince;

    /**
     * 市
     */
    private String contactCity;

    /**
     * 区
     */
    private String contactArea;

    /**
     * 联系人地址
     */
    private String contactAddress;

    /**
     * 操作用户
     */
    private String perationUser;
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 公司编码
     */
    private String companyCode;
}
