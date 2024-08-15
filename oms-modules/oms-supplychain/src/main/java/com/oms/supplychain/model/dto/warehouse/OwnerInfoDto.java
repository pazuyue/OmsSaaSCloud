package com.oms.supplychain.model.dto.warehouse;

import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import lombok.Data;

import java.util.Date;

@Data
public class OwnerInfoDto {

    private Integer id;
    private String ownerCode;
    private String ownerName;
    private Byte isSync;
    private Byte isEnable;
    private String companyCode;
    private Date createTime;
    private Date modifyTime;
    private WmsRealStoreInfo realStoreInfo;
}
