package com.oms.inventory.factory;

import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.service.rule.AllocationStrategyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// 策略工厂
@Component
public class StrategyFactory {
    private final Map<String, AllocationStrategyService> strategies = new ConcurrentHashMap<>();


    @Autowired
    public StrategyFactory(List<AllocationStrategyService> strategyList) {
        strategyList.forEach(strategy -> {
            StrategyType annotation = strategy.getClass()
                    .getAnnotation(StrategyType.class);
            if (annotation != null) {
                strategies.put(annotation.value(), strategy);
            }
        });
    }

    public AllocationStrategyService getStrategy(String type) {
        return Optional.ofNullable(strategies.get(type))
                .orElseThrow(() -> new IllegalArgumentException("无效的策略类型: " + type));
    }
}
