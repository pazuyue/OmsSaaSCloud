package com.oms.inventory.service.impl.rule;


import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.model.dto.rule.AllocationResult;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.service.rule.AllocationStrategyService;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@StrategyType("OVER_ALLOCATE")
@Component
public class OverAllocateStrategyImpl implements AllocationStrategyService {
    @Override
    public AllocationResult allocate(RuleStockInfo rule) {
        return null;
    }
}
