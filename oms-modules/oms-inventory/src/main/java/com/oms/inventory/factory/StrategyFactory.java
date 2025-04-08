package com.oms.inventory.factory;

import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.service.rule.AllocationStrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class StrategyFactory {
    private final Map<String, AllocationStrategyService> strategies = new ConcurrentHashMap<>();

    @Autowired
    public StrategyFactory(List<AllocationStrategyService> strategyList) {
        if (strategyList == null || strategyList.isEmpty()) {
            throw new IllegalArgumentException("策略列表不能为空");
        }
        strategyList.forEach(strategy -> {
            log.debug("strategy:{}", strategy);
            StrategyType annotation = getStrategyTypeAnnotation(strategy.getClass());
            if (annotation != null) {
                String type = annotation.value();
                strategies.put(type, strategy);
                log.debug("Registered strategy: {}", type);
            } else {
                log.warn("Strategy class {} does not have @StrategyType annotation", strategy.getClass().getName());
            }
        });
        log.debug("All registered strategies: {}", strategies.keySet());
    }

    private StrategyType getStrategyTypeAnnotation(Class<?> clazz) {
        StrategyType annotation = clazz.getAnnotation(StrategyType.class);
        if (annotation == null && clazz.getSuperclass() != null) {
            return getStrategyTypeAnnotation(clazz.getSuperclass());
        }
        return annotation;
    }

    public AllocationStrategyService getStrategy(String type) {
        log.debug("getStrategy type:{}", type);
        log.debug("strategies:{}", strategies);
        return Optional.ofNullable(strategies.get(type))
                .orElseThrow(() -> new IllegalArgumentException("无效的策略类型: " + type));
    }
}
