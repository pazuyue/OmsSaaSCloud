package com.oms.supplychain.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 实仓表 Mapper 接口
 * </p>
 *
 * @author 月光光
 * @since 2023-07-18
 */
public interface WmsRealStoreInfoMapper extends BaseMapper<WmsRealStoreInfo> {

    @Select("select * from wms_real_store_info WHERE real_store_code =#{real_store_code}")
    WmsRealStoreInfo selectRealStoreInfoByRealStoreCode(String real_store_code);
}
