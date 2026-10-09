package com.oms.inventory.service.impl.rule;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.rule.RuleStockInfoMapper;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.service.rule.IRuleStockInfoService;
import com.ruoyi.common.core.utils.uuid.IdUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class RuleStockInfoServiceImpl extends ServiceImpl<RuleStockInfoMapper,RuleStockInfo> implements IRuleStockInfoService {
    @Override
    public RuleStockInfo selectRuleStockInfoById(Long id) {
        RuleStockInfo row=this.getById(id);
        if(row==null || !com.oms.inventory.service.InventoryCompany.current().equalsIgnoreCase(row.getCompanyCode()))throw new IllegalArgumentException("分货单不存在或无权访问");
        return row;
    }

    @Override
    public List<RuleStockInfo> selectRuleStockInfoList(RuleStockInfo ruleStockInfo) {
        QueryWrapper<RuleStockInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("company_code",com.oms.inventory.service.InventoryCompany.current());
        queryWrapper.eq(ruleStockInfo.getEnable()!=null,"enable",ruleStockInfo.getEnable());
        queryWrapper.eq(ruleStockInfo.getAllocationType()!=null,"allocation_type",ruleStockInfo.getAllocationType());
        queryWrapper.eq(ObjectUtil.isNotEmpty(ruleStockInfo.getRuleName()),"rule_name",ruleStockInfo.getRuleName());
        queryWrapper.eq(ObjectUtil.isNotEmpty(ruleStockInfo.getRuleCode()),"rule_code",ruleStockInfo.getRuleCode());
        queryWrapper.eq(ObjectUtil.isNotNull(ruleStockInfo.getStatus()),"status",ruleStockInfo.getStatus());
        queryWrapper.eq(ObjectUtil.isNotEmpty(ruleStockInfo.getRuleType()),"rule_type",ruleStockInfo.getRuleType());
        queryWrapper.ge(ObjectUtil.isNotEmpty(ruleStockInfo.getCreateTime()),"create_time",ruleStockInfo.getCreateTime());
        queryWrapper.orderByDesc("id");
        return this.list(queryWrapper);
    }

    @Override
    public int insertRuleStockInfo(RuleStockInfo ruleStockInfo) {
        String ruleCode = IdUtils.fastUUID();
        ruleStockInfo.setRuleCode(ruleCode);
        String operName = SecurityUtils.getUsername();
        ruleStockInfo.setCreateUserName(operName);
        return this.baseMapper.insert(ruleStockInfo);
    }

    @Override
    public int updateRuleStockInfo(RuleStockInfo ruleStockInfo) {
        return this.baseMapper.updateById(ruleStockInfo);
    }

    @Override
    public int deleteRuleStockInfoByIds(Long[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteRuleStockInfoById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
