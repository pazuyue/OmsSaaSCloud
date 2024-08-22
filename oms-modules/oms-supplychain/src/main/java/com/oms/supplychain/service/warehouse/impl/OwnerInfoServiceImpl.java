package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.OwnerInfoMapper;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.vo.warehouse.OwnerInfoVO;
import com.oms.supplychain.service.warehouse.OwnerInfoService;
import com.ruoyi.common.core.utils.DateUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

@Service
public class OwnerInfoServiceImpl extends ServiceImpl<OwnerInfoMapper, OwnerInfo> implements OwnerInfoService {


    @Override
    public OwnerInfo findOneByOwnerInfo(OwnerInfo vo) {
        String ownerCode = vo.getOwnerCode();
        QueryWrapper<OwnerInfo> queryWrapper = new <OwnerInfo>QueryWrapper();
        if (!StrUtil.isBlank(ownerCode)){
            queryWrapper.eq("owner_code",ownerCode);
        }
        return this.getOne(queryWrapper);
    }

    @Override
    public OwnerInfo selectOwnerInfoById(Integer id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<OwnerInfo> selectOwnerInfoList(OwnerInfo ownerInfo) {
        QueryWrapper<OwnerInfo> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(ownerInfo.getOwnerCode())){
            queryWrapper.eq("owner_code",ownerInfo.getOwnerCode());
        }
        if (!StrUtil.isBlank(ownerInfo.getOwnerName()))
        {
            queryWrapper.like("owner_name",ownerInfo.getOwnerName());
        }
        if (!ObjectUtil.isNull(ownerInfo.getIsEnable())){
            queryWrapper.eq("is_enable",ownerInfo.getIsEnable());
        }
        if (!ObjectUtil.isNull(ownerInfo.getIsSync())){
            queryWrapper.eq("is_sync",ownerInfo.getIsSync());
        }
        queryWrapper.orderByDesc("modify_time");
        return this.list(queryWrapper);
    }

    @Override
    public List<OwnerInfo> listOwner(String companyCode) {
        QueryWrapper<OwnerInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("company_code",companyCode);
        queryWrapper.eq("is_enable",1);
        queryWrapper.orderByDesc("modify_time");
        queryWrapper.select("owner_code","owner_name"); // 选择 owner_code 字段
        return this.list(queryWrapper);
    }

    @Override
    public int insertOwnerInfo(OwnerInfo ownerInfo,String companyCode) {
        OwnerInfo one = findOneByOwnerInfo(ownerInfo);
        if (!ObjectUtil.isEmpty(one))
            throw new RuntimeException("货主编码"+one.getOwnerCode()+"已存在");
        ownerInfo.setCreateTime(DateUtils.getNowDate());
        ownerInfo.setCompanyCode(companyCode);
        return this.baseMapper.insert(ownerInfo);
    }

    @Override
    public boolean updateOwnerInfo(OwnerInfo ownerInfo) {
        ownerInfo.setModifyTime(DateUtils.getNowDate());
        return this.updateById(ownerInfo);
    }

    @Override
    public int deleteOwnerInfoByIds(Integer[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteOwnerInfoById(Integer id) {
        return this.baseMapper.deleteById(id);
    }
}