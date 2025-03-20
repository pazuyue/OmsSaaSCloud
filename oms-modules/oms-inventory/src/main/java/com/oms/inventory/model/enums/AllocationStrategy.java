package com.oms.inventory.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AllocationStrategy {
    PRIORITY(1, "优先分配"),       // 按渠道优先级分配
    PROPORTIONAL(2, "等比例压缩"), // 总量压缩至可用库存
    OVER_ALLOCATE(3, "超额分配");  // 直接分配不扣减库存

    @EnumValue
    private final int code;
    @JsonValue
    public String remark;
}
