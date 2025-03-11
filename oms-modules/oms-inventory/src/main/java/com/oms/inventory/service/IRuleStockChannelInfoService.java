package com.oms.inventory.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.dto.AllocationRuleDto;
import com.oms.inventory.model.entity.RuleStockChannelInfo;

public interface IRuleStockChannelInfoService extends IService<RuleStockChannelInfo> {

    /**
     * 设置分货规则
     * @param dto
     * @return
     */
    Boolean setRule(AllocationRuleDto dto);
}
