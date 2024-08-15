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
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
public class OwnerInfoServiceImpl extends ServiceImpl<OwnerInfoMapper, OwnerInfo> implements OwnerInfoService {

    @Override
    public boolean save(OwnerInfoVO vo,String companyCode) {
        OwnerInfo ownerInfo = new OwnerInfo();
        OwnerInfo one = findOneByOwnerInfoVO(vo);
        if (!ObjectUtil.isEmpty(one))
            throw new RuntimeException("货主编码"+one.getOwnerCode()+"已存在");
        BeanUtil.copyProperties(vo,ownerInfo);
        ownerInfo.setCompanyCode(companyCode);
        if (this.save(ownerInfo))
            return true;
        return false;
    }

    @Override
    public OwnerInfo findOneByOwnerInfoVO(OwnerInfoVO vo) {
        String ownerCode = vo.getOwnerCode();
        QueryWrapper<OwnerInfo> queryWrapper = new <OwnerInfo>QueryWrapper();
        if (!StrUtil.isBlank(ownerCode)){
            queryWrapper.eq("owner_code",ownerCode);
        }
        return this.getOne(queryWrapper);
    }

    @Override
    public List<OwnerInfo> list(String owner_code, Integer page, Integer pageSize) {
        QueryWrapper<OwnerInfo> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(owner_code)){
            queryWrapper.eq("owner_code",owner_code);
        }
        queryWrapper.orderByDesc("modify_time");
        return this.list(queryWrapper);

    }

}
