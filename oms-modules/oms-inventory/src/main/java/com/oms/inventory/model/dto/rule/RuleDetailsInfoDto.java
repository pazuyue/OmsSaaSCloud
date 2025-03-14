package com.oms.inventory.model.dto.rule;

import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import lombok.Data;

import java.util.List;

@Data
public class RuleDetailsInfoDto {
    private List<RuleStockChannelInfo> channelInfoList;
    private List<RuleStockStoreCodeInfo> storeCodeInfoList;
}
