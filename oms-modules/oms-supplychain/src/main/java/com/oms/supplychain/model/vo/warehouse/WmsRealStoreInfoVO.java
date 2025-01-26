package com.oms.supplychain.model.vo.warehouse;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
public class WmsRealStoreInfoVO {

    private Long id;
    /**
     * 1:电商仓，2：门店仓，3：零售仓
     */
    @NotNull(message = "实仓类型不能为空")
    private Integer wmsType;
    /**
     * 实仓编码
     */
    @NotBlank(message = "实仓编码不能为空")
    private String realStoreCode;

    /**
     * 实仓名称
     */
    @NotBlank(message = "实仓名称不能为空")
    private String wmsName;

    /**
     * 仓库负责人
     */
    @NotBlank(message = "仓库负责人不能为空")
    private String director;

    /**
     * 联系电话
     */
    @NotBlank(message = "联系电话不能为空")
    private String mobilePhone;

    /**
     * 省
     */
    @NotBlank(message = "省不能为空")
    private String province;

    /**
     * 市
     */
    @NotBlank(message = "市不能为空")
    private String city;

    /**
     * 区
     */
    @NotBlank(message = "区不能为空")
    private String district;

    /**
     * 地址
     */
    @NotBlank(message = "地址不能为空")
    private String address;

    /**
     * 公司编码
     */
    private String companyCode;

    @NotNull(message = "出入库类型不能为空")
    private Integer actualWarehouse;
}
