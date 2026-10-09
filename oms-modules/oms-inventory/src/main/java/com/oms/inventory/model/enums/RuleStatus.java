package com.oms.inventory.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RuleStatus {

    NEW(1,"新建"),          // 新建
    PENDING_REVIEW(2,"待审核"), // 待审核
    PENDING_EXECUTION(3,"待执行"), // 待执行
    EXECUTING(4,"执行中"),     // 执行中
    COMPLETED(5,"已结束"),     // 已结束
    CANCELLED(6,"已作废"),
    PARTIAL(7,"部分成功"),
    FAILED(8,"执行失败"),
    RELEASING(9,"释放中"),
    RELEASED(10,"释放完成"),
    PARTIALLY_RELEASED(11,"部分释放"),
    DAILY_PAUSED(12,"已停用"),
    DAILY_EXPIRED(13,"已到期");


    @EnumValue
    private final int value;
    @JsonValue
    public String remark;

    public static RuleStatus fromValue(int value) {
        for (RuleStatus status : RuleStatus.values()) {
            if (status.getValue() == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("No enum constant for value: " + value);
    }
}
