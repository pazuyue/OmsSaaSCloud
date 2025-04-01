package com.oms.inventory.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AllocationStrategy {
    OVER_ALLOCATE(1,"普通分配"), // 直接分配不扣减库存
    PRIORITY(2, "优先分配"),       // 按渠道优先级分配
    PROPORTIONAL(3, "等比例压缩"); // 总量压缩至可用库存

    @EnumValue
    private final int code;
    @JsonValue
    public String remark;
}
