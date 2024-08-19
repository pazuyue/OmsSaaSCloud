package com.oms.supplychain.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.supplychain.model.dto.warehouse.OwnerInfoDto;
import com.oms.supplychain.model.dto.warehouse.SimulationStoreInfoDto;
import com.oms.supplychain.model.entity.warehouse.WmsSimulationStoreInfo;
import org.apache.ibatis.annotations.One;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 虚仓表 Mapper 接口
 * </p>
 *
 * @author 月光光
 * @since 2023-08-02
 */
public interface WmsSimulationStoreInfoMapper extends BaseMapper<WmsSimulationStoreInfo> {

    @Select("select * from wms_simulation_store_info where wms_simulation_code=#{wms_simulation_code}")
    @Results({
            @Result(property = "ownerCode", column = "owner_code"),
            @Result(property = "ownerInfo", column = "owner_code", javaType = OwnerInfoDto.class,
                    one = @One(select = "com.oms.saas.commodity.mapper.Warehouse.OwnerInfoMapper.selectOwnerInfoByOwnerCodeWithRealStore"))
    })
    SimulationStoreInfoDto selectSimulationStoreInfoWtihOwnerInfo(String wms_simulation_code);
}
