package com.oms.inventory.service.rule;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockGoodsInfo;

import java.util.List;

public interface RuleStockGoodsInfoService extends IService<RuleStockGoodsInfo> {

    public List<RuleStockGoodsInfo> selectRuleStockGoodsInfoList(Long ruleId,String[] skuSnArray);
}
