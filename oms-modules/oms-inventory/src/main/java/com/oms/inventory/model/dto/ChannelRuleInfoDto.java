package com.oms.inventory.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelRuleInfoDto {
    private String channelName;      // 渠道名称
    private String priority;         // 优先级
    private String stockBase;        // 库存基准
    private BigDecimal percentage;      // 百分比
    private Integer decimalHandleType; // 小数处理类型
    private Integer channelId;       // 渠道ID
}
