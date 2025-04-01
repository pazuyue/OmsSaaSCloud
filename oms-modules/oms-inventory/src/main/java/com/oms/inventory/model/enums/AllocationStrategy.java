package com.oms.inventory.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AllocationStrategy {
    OVER_ALLOCATE(1,"OVER_ALLOCATE"), // 直接分配不扣减库存
    PRIORITY(2, "PRIORITY"),       // 按渠道优先级分配
    PROPORTIONAL(3, "PROPORTIONAL"); // 总量压缩至可用库存

    @EnumValue
    private final Integer code;
    @JsonValue
    public String remark;
}
