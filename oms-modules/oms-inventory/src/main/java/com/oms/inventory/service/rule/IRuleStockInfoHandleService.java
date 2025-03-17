package com.oms.inventory.service.rule;

import com.oms.inventory.model.dto.AllocationRuleDto;
import com.oms.inventory.model.dto.rule.RuleDetailsInfoDto;

public interface IRuleStockInfoHandleService {

    /**
     * 设置分货规则
     * @param dto
     * @return
     */
    Boolean setRule(AllocationRuleDto dto);

    /**
     * 获取分货明细
     * @param id
     * @return
     */
    public RuleDetailsInfoDto getInfoDetails(Long id,String companyCode);
}
