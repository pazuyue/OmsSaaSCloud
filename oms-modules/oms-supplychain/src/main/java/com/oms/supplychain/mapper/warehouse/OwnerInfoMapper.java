package com.oms.supplychain.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.supplychain.model.dto.warehouse.OwnerInfoDto;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import org.apache.ibatis.annotations.One;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 货主基础信息表 Mapper 接口
 * </p>
 *
 * @author 月光光
 * @since 2023-07-18
 */
public interface OwnerInfoMapper extends BaseMapper<OwnerInfo> {

    @Select("select * from owner_info WHERE owner_code =#{owner_code}")
    OwnerInfo selectOwnerInfoByOwnerCode(String owner_code);

    @Select("select * from owner_info WHERE owner_code =#{owner_code}")
    @Results({
            @Result(property = "ownerCode", column = "owner_code"),
            @Result(property = "realStoreInfo", column = "owner_code", javaType = WmsRealStoreInfo.class,
                    one = @One(select = "com.oms.saas.commodity.mapper.Warehouse.WmsRealStoreInfoMapper.selectRealStoreInfoByOwnerCode"))
    })
    OwnerInfoDto selectOwnerInfoByOwnerCodeWithRealStore(String owner_code);
}
