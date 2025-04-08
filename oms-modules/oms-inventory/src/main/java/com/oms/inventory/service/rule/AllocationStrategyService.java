package com.oms.inventory.service.rule;

import com.oms.inventory.model.entity.rule.RuleStockInfo;

public interface AllocationStrategyService {

    /**
     * 执行库存分配算法
     * @param rule 分配请求对象
     * @return 分配结果
     */
    Boolean allocate(RuleStockInfo rule);
}
