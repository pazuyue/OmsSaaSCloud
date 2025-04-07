package com.oms.inventory.model.dto.rule;

import com.oms.inventory.model.enums.AllocationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@AllArgsConstructor
@Data
public class AllocationResult {
    private List<AllocationDetail> details;
    private BigDecimal remainingStock;
    private AllocationStatus status;
}
