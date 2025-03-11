package com.oms.inventory.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum RuleStatus {

    NEW(1),          // 新建
    PENDING_REVIEW(2), // 待审核
    PENDING_EXECUTION(3), // 待执行
    EXECUTING(4),     // 执行中
    COMPLETED(5),     // 已结束
    CANCELLED(6);     // 已作废


    @EnumValue
    private final int value;

    RuleStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static RuleStatus fromValue(int value) {
        for (RuleStatus status : RuleStatus.values()) {
            if (status.getValue() == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("No enum constant for value: " + value);
    }
}
