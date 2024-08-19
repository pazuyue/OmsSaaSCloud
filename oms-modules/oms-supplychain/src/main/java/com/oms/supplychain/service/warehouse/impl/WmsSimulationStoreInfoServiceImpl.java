package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsSimulationStoreInfoMapper;
import com.oms.supplychain.model.dto.warehouse.SimulationStoreInfoDto;
import com.oms.supplychain.model.entity.warehouse.WmsSimulationStoreInfo;
import com.oms.supplychain.service.warehouse.WmsSimulationStoreInfoService;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 虚仓表 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-02
 */
@Service
@CacheConfig(cacheNames = "simulationStoreInfo")
public class WmsSimulationStoreInfoServiceImpl extends ServiceImpl<WmsSimulationStoreInfoMapper, WmsSimulationStoreInfo> implements WmsSimulationStoreInfoService {



    @Cacheable(value = {"simulationStoreInfo"},key = "#wms_simulation_code")
    public SimulationStoreInfoDto getSimulationStoreInfoDto(String wms_simulation_code){
        return this.baseMapper.selectSimulationStoreInfoWtihOwnerInfo(wms_simulation_code);
    }

    @Override
    public boolean save(WmsSimulationStoreInfo wmsSimulationStoreInfo,String companyCode) {
        wmsSimulationStoreInfo.setCompanyCode(companyCode);
        return this.save(wmsSimulationStoreInfo);
    }
}
