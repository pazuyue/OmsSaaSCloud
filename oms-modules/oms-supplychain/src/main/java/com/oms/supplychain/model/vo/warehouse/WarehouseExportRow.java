package com.oms.supplychain.model.vo.warehouse;
import lombok.Data;
import com.ruoyi.common.core.annotation.Excel;
@Data public class WarehouseExportRow {
    @Excel(name="货主编码") private String ownerCode;
    @Excel(name="货主名称") private String ownerName;
    @Excel(name="启用状态", readConverterExp="1=停用,2=启用") private Integer isEnable;
    @Excel(name="关联实仓数") private Integer warehouseCount;
    @Excel(name="关联货主数") private Integer ownerCount;
    @Excel(name="虚仓数") private Integer virtualCount;
    @Excel(name="实体仓库编码") private String realStoreCode;
    @Excel(name="实体仓库名称") private String wmsName;
    @Excel(name="启用状态", readConverterExp="1=停用,2=启用") private Integer status;
    @Excel(name="仓库类型", readConverterExp="1=电商仓,2=门店仓,3=零售仓") private Integer wmsType;
    @Excel(name="出入库模式", readConverterExp="1=真实出入库,2=虚拟出入库") private Integer actualWarehouse;
    @Excel(name="负责人") private String director;
    @Excel(name="联系电话") private String mobilePhone;
    @Excel(name="省") private String province;
    @Excel(name="市") private String city;
    @Excel(name="区") private String district;
    @Excel(name="地址") private String address;
    @Excel(name="虚仓编码") private String wmsSimulationCode;
    @Excel(name="虚仓名称") private String wmsSimulationName;
    @Excel(name="业务可用", readConverterExp="0=不可用,1=可用") private Integer effectiveEnabled;
    @Excel(name="修改时间", dateFormat="yyyy-MM-dd HH:mm:ss", width=24) private java.util.Date modifyTime;
}
