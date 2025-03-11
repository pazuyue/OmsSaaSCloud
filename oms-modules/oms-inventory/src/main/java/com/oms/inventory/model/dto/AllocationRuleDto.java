package com.oms.inventory.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AllocationRuleDto {
    private List<Integer> channelIds;             // 渠道ID列表
    private List<String> wmsSimulationCodes;     // WMS模拟编码列表
    private Integer priorityType;               // 优先级类型
    private List<ChannelRuleInfoDto> infoList;          // 渠道信息列表
    private Integer ruleId;                      // 规则ID
    private String companyCode;
}
