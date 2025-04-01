package com.oms.inventory.context;

import com.oms.inventory.model.dto.rule.ChannelAllocationRule;
import com.oms.inventory.model.dto.rule.VirtualWarehouse;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// 2. 上下文对象（包含分配所需全部参数）
@Data
@AllArgsConstructor
public class AllocationContext {
    private List<VirtualWarehouse> warehouses;
    private List<ChannelAllocationRule> rules;
    private BigDecimal totalRequired;
    private Map<String, Object> extParams;
}
