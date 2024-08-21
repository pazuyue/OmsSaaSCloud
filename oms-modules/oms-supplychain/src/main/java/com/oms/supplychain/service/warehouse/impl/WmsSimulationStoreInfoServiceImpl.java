package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsSimulationStoreInfoMapper;
import com.oms.supplychain.model.dto.warehouse.SimulationStoreInfoDto;
import com.oms.supplychain.model.entity.warehouse.WmsSimulationStoreInfo;
import com.oms.supplychain.service.warehouse.WmsSimulationStoreInfoService;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

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
    public WmsSimulationStoreInfo selectWmsSimulationStoreInfoById(Long id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<WmsSimulationStoreInfo> selectWmsSimulationStoreInfoList(WmsSimulationStoreInfo wmsSimulationStoreInfo) {
        QueryWrapper<WmsSimulationStoreInfo> queryWrapper = new QueryWrapper();
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsSimulationStoreInfo.getWmsSimulationCode()),"wms_simulation_code",wmsSimulationStoreInfo.getWmsSimulationCode());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsSimulationStoreInfo.getWmsSimulationName()),"wms_simulation_name",wmsSimulationStoreInfo.getWmsSimulationName());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsSimulationStoreInfo.getOwnerCode()),"owner_code",wmsSimulationStoreInfo.getOwnerCode());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsSimulationStoreInfo.getOwnerName()),"owner_name",wmsSimulationStoreInfo.getOwnerName());
        queryWrapper.eq(ObjectUtil.isNotNull(wmsSimulationStoreInfo.getStatus()),"status",wmsSimulationStoreInfo.getStatus());
        return this.baseMapper.selectList(queryWrapper);
    }

    @Override
    public List<WmsSimulationStoreInfo> listSimulationStore(String companyCode) {
        QueryWrapper<WmsSimulationStoreInfo> queryWrapper = new QueryWrapper();
        queryWrapper.eq("company_code",companyCode);
        queryWrapper.eq("status",1);
        queryWrapper.select("wms_simulation_code","wms_simulation_name"); // 选择  字段
        return this.baseMapper.selectList(queryWrapper);
    }

    @Override
    public int insertWmsSimulationStoreInfo(WmsSimulationStoreInfo wmsSimulationStoreInfo, String companyCode) {
        wmsSimulationStoreInfo.setCompanyCode(companyCode);
        return this.baseMapper.insert(wmsSimulationStoreInfo);
    }

    @Override
    public boolean updateWmsSimulationStoreInfo(WmsSimulationStoreInfo wmsSimulationStoreInfo) {
        return this.updateById(wmsSimulationStoreInfo);
    }

    @Override
    public int deleteWmsSimulationStoreInfoByIds(Long[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteWmsSimulationStoreInfoById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
