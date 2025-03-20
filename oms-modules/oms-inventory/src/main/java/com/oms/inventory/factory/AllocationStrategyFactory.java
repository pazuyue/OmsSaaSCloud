package com.oms.inventory.factory;

import com.oms.inventory.model.enums.AllocationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import static com.oms.inventory.model.enums.AllocationStrategy.PRIORITY;
import static com.oms.inventory.model.enums.AllocationStrategy.PROPORTIONAL;
import static com.oms.inventory.model.enums.AllocationStrategy.OVER_ALLOCATE;

@Slf4j
@Service
public class AllocationStrategyFactory {
    public static AllocationStrategy getStrategy(int ruleType) {
        switch (ruleType) {
            case 1: return PRIORITY;
            case 2: return PROPORTIONAL;
            case 3: return OVER_ALLOCATE;
            default: throw new IllegalArgumentException("未知分配规则");
        }
    }
}
