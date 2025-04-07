package com.oms.inventory.model.dto.rule;

import com.oms.inventory.model.enums.AllocationStrategy;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ChannelAllocationRule {
    private Long id;
    private Integer channelId;
    private String channelName;
    private AllocationStrategy strategyType; // 枚举类型
    private BigDecimal allocationRatio;
    private Integer priority;
    private Integer status;
}
