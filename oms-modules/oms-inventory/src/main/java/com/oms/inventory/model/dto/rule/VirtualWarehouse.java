package com.oms.inventory.model.dto.rule;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class VirtualWarehouse {
    private Long id;
    private String code;
    private BigDecimal availableStock;
    private LocalDateTime update_time;
}
