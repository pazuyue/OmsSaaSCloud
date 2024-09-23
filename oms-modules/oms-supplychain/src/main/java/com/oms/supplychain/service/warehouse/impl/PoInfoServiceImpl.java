package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.PoInfoMapper;
import com.oms.supplychain.model.entity.warehouse.PoInfo;
import com.oms.supplychain.service.warehouse.PoInfoService;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 采购单主表 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-07-20
 */
@Service
public class PoInfoServiceImpl extends ServiceImpl<PoInfoMapper, PoInfo> implements PoInfoService {


    @Override
    public PoInfo selectPoInfoById(Integer id) {
        return this.getById(id);
    }

    @Override
    public List<PoInfo> selectPoInfoList(PoInfo poInfo) {
        QueryWrapper<PoInfo> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(poInfo.getPoName())){
            queryWrapper.eq("po_name",poInfo.getPoName());
        }
        if (!StrUtil.isBlank(poInfo.getPoSn())){
            queryWrapper.eq("po_sn",poInfo.getPoSn());
        }
        if (!StrUtil.isBlank(poInfo.getWmsSimulationCode())){
            queryWrapper.eq("wms_simulation_code",poInfo.getWmsSimulationCode());
        }
        if (!StrUtil.isBlank(poInfo.getDepartmentCode())){
            queryWrapper.eq("department_code",poInfo.getDepartmentCode());
        }
        if (!StrUtil.isBlank(poInfo.getSupplierSn())){
            queryWrapper.eq("supplier_sn",poInfo.getSupplierSn());
        }
        if (!StrUtil.isBlank(poInfo.getPerationUser())){
            queryWrapper.eq("peration_user",poInfo.getPerationUser());
        }
        if (!ObjectUtil.isEmpty(poInfo.getCreateTime())){
            queryWrapper.ge("create_time",poInfo.getCreateTime());
        }
        if (!ObjectUtil.isEmpty(poInfo.getModifyTime())){
            queryWrapper.ge("modify_time",poInfo.getModifyTime());
        }
        if (!ObjectUtil.isEmpty(poInfo.getPoState())){
            queryWrapper.eq("po_state",poInfo.getPoState());
        }
        if (!ObjectUtil.isEmpty(poInfo.getActualWarehouse())){
            queryWrapper.eq("actual_warehouse",poInfo.getActualWarehouse());
        }
        queryWrapper.orderByDesc("modify_time");
        return this.list(queryWrapper);
    }

    @Override
    public int insertPoInfo(PoInfo poInfo, String companyCode) {
        String poSn = "PO"+ IdUtil.simpleUUID();
        String operName = SecurityUtils.getUsername();
        poInfo.setPoSn(poSn);
        poInfo.setPerationUser(operName);
        poInfo.setCompanyCode(companyCode);

        return this.baseMapper.insert(poInfo);
    }

    @Override
    public int updatePoInfo(PoInfo poInfo) {
        return this.baseMapper.updateById(poInfo);
    }

    @Override
    public boolean deletePoInfoById(Integer id) {
        PoInfo poInfo = new PoInfo();
        poInfo.setPoState(-1);
        poInfo.setId(id);
        return this.updateById(poInfo);
    }

    @Override
    public boolean deletePoInfoByIds(Integer[] ids) {
        List<PoInfo> poInfoList = new ArrayList<>();
        for (Integer id : ids) {
            PoInfo poInfo = new PoInfo();
            poInfo.setPoState(-1);
            poInfo.setId(id);
            poInfoList.add(poInfo);
        }
        return this.updateBatchById(poInfoList);
    }
}
