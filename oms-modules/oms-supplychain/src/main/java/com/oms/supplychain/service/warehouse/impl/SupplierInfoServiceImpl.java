package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.SupplierInfoMapper;
import com.oms.supplychain.model.entity.warehouse.SupplierInfo;
import com.oms.supplychain.service.warehouse.SupplierInfoService;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * 供应商主表 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-07-19
 */
@Service
public class SupplierInfoServiceImpl extends ServiceImpl<SupplierInfoMapper, SupplierInfo> implements SupplierInfoService {

    public SupplierInfo findOneBySupplierInfo(SupplierInfo vo) {
        QueryWrapper<SupplierInfo> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(vo.getSupplierName())){
            queryWrapper.eq("supplier_name",vo.getSupplierName());
        }
        if (!StrUtil.isBlank(vo.getCompanyName())){
            queryWrapper.eq("company_name",vo.getCompanyName());
        }
        return this.getOne(queryWrapper);
    }

    @Override
    public SupplierInfo selectSupplierInfoById(Integer id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<SupplierInfo> selectSupplierInfoList(SupplierInfo supplierInfo) {
        QueryWrapper<SupplierInfo> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(supplierInfo.getSupplierName())){
            queryWrapper.eq("supplier_name",supplierInfo.getSupplierName());
        }
        if (!StrUtil.isBlank(supplierInfo.getCompanyName())){
            queryWrapper.eq("company_name",supplierInfo.getCompanyName());
        }
        if (!StrUtil.isBlank(supplierInfo.getSupplierSn())){
            queryWrapper.eq("supplier_sn",supplierInfo.getSupplierSn());
        }
        if (!StrUtil.isBlank(supplierInfo.getPerationUser())){
            queryWrapper.eq("peration_user",supplierInfo.getPerationUser());
        }
        if (!ObjectUtil.isEmpty(supplierInfo.getCreateTime())){
            queryWrapper.ge("create_time",supplierInfo.getCreateTime());
        }
        if (!ObjectUtil.isEmpty(supplierInfo.getModifyTime())){
            queryWrapper.ge("modify_time",supplierInfo.getModifyTime());
        }

        queryWrapper.orderByDesc("modify_time");
        return this.list(queryWrapper);
    }

    @Override
    public int insertSupplierInfo(SupplierInfo supplierInfo, String companyCode) {
        SupplierInfo one = findOneBySupplierInfo(supplierInfo);
        if (!ObjectUtil.isEmpty(one))
            throw new RuntimeException("供应商主表"+one.getSupplierName()+"已存在");
        String po_sn =  IdUtil.simpleUUID();
        supplierInfo.setSupplierSn(po_sn);
        supplierInfo.setCompanyCode(companyCode);
        String operName = SecurityUtils.getUsername();
        supplierInfo.setPerationUser(operName);
        return this.baseMapper.insert(supplierInfo);
    }

    @Override
    public int updateSupplierInfo(SupplierInfo supplierInfo) {
        supplierInfo.setModifyTime(DateUtils.getNowDate());
        String operName = SecurityUtils.getUsername();
        supplierInfo.setPerationUser(operName);
        supplierInfo.setCreateTime(null);
        return this.baseMapper.updateById(supplierInfo);
    }

    @Override
    public int deleteSupplierInfoByIds(Integer[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteSupplierInfoById(Integer id) {
        return this.baseMapper.deleteById(id);
    }
}
