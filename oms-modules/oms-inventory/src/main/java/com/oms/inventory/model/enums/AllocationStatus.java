package com.oms.inventory.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.stream.Stream;

/**
 * 库存分配状态枚举类
 */
@Getter
@AllArgsConstructor
public enum AllocationStatus {

    /**
     * 分配成功
     */
    SUCCESS(200, "分配成功", true),

    /**
     * 部分成功（存在未分配库存）
     */
    PARTIAL_SUCCESS(207, "部分成功", false),

    /**
     * 分配失败
     */
    FAILURE(500, "分配失败", false),

    /**
     * 处理中（异步任务执行中）
     */
    PROCESSING(202, "处理中", true),

    /**
     * 已取消
     */
    CANCELLED(400, "操作已取消", false),

    /**
     * 验证失败（参数/库存校验不通过）
     */
    VALIDATION_FAILED(401, "参数验证失败", false);

    private final int code;
    private final String description;
    private final boolean retryable; // 是否可重试

    /**
     * 根据状态码获取枚举实例
     * @param code 状态码
     * @return 对应的枚举实例
     */
    public static AllocationStatus fromCode(int code) {
        return Stream.of(values())
                .filter(status -> status.getCode() == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid status code: " + code));
    }

    /**
     * 判断是否为最终状态（不可重试）
     * @return 是否是最终状态
     */
    public boolean isFinalState() {
        return this == FAILURE || this == CANCELLED;
    }

    /**
     * 判断是否需要人工介入
     * @return 是否需要人工处理
     */
    public boolean requiresAttention() {
        return this == FAILURE || this == VALIDATION_FAILED;
    }
}
