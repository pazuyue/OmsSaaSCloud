package com.oms.inventory.service.impl.rule;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.rule.RuleStockGoodsInfoMapper;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockGoodsInfo;
import com.oms.inventory.service.rule.RuleStockGoodsInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RuleStockGoodsInfoServiceImpl extends ServiceImpl<RuleStockGoodsInfoMapper, RuleStockGoodsInfo> implements RuleStockGoodsInfoService {

    @Override
    public List<RuleStockGoodsInfo> selectRuleStockGoodsInfoList(Long ruleId, String[] skuSnArray) {
        QueryWrapper<RuleStockGoodsInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("rule_id",ruleId);
        queryWrapper.in("sku_sn",skuSnArray);
        return this.list(queryWrapper);
    }
}
