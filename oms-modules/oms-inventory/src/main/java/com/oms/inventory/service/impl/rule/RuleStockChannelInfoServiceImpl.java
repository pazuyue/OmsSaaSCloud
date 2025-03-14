package com.oms.inventory.service.impl.rule;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.rule.RuleStockChannelInfoMapper;
import com.oms.inventory.model.dto.AllocationRuleDto;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.model.enums.RuleStatus;
import com.oms.inventory.service.rule.IRuleStockChannelInfoService;
import com.oms.inventory.service.rule.IRuleStockInfoService;
import com.oms.inventory.service.rule.IRuleStockStoreCodeInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RuleStockChannelInfoServiceImpl extends ServiceImpl<RuleStockChannelInfoMapper, RuleStockChannelInfo> implements IRuleStockChannelInfoService {

    @Resource
    private IRuleStockInfoService ruleStockInfoService;
    @Resource
    private IRuleStockStoreCodeInfoService ruleStockStoreCodeInfoService;

    @Transactional
    public Boolean setRule(AllocationRuleDto dto) {
        try {
            // 空指针检查
            if (dto == null || dto.getInfoList() == null) {
                log.warn("Input DTO or info list is null");
                return false;
            }

            RuleStockInfo one = ruleStockInfoService.getById(dto.getRuleId());
            if (one == null || one.getStatus() != RuleStatus.NEW) {
                log.warn("RuleStockInfo not found or status is not ACTIVE");
                return false;
            }

            List<RuleStockChannelInfo> list = dto.getInfoList().stream()
                    .map(item -> {
                        RuleStockChannelInfo ruleStockChannelInfo = new RuleStockChannelInfo();
                        ruleStockChannelInfo.setChannelId(item.getChannelId());
                        ruleStockChannelInfo.setPercentage(item.getPercentage());
                        ruleStockChannelInfo.setDecimalHandleType(item.getDecimalHandleType());
                        ruleStockChannelInfo.setRuleId(dto.getRuleId());
                        ruleStockChannelInfo.setCompanyCode(dto.getCompanyCode());
                        return ruleStockChannelInfo;
                    })
                    .collect(Collectors.toList());

            log.debug("setRule list:{}", list);

            log.debug("setRule WmsSimulationCodes:{}", dto.getWmsSimulationCodes());

            List<RuleStockStoreCodeInfo> storeCodeInfoList = new ArrayList<>();
            for (String wmsSimulationCode : dto.getWmsSimulationCodes()) {
                RuleStockStoreCodeInfo info = new RuleStockStoreCodeInfo();
                info.setRuleId(dto.getRuleId());
                info.setStoreCode(wmsSimulationCode);
                info.setCompanyCode(dto.getCompanyCode());
                storeCodeInfoList.add(info);
            }

            log.debug("setRule storeCodeInfoList:{}", storeCodeInfoList);
            // 保存规则-仓库信息
            ruleStockStoreCodeInfoService.saveBatch(storeCodeInfoList);

            one.setStatus(RuleStatus.PENDING_REVIEW);
            boolean saveResult = this.saveBatch(list);
            if (!saveResult) {
                log.error("Failed to save batch data");
                return false;
            }

            boolean updateResult = ruleStockInfoService.updateById(one);
            if (!updateResult) {
                log.error("Failed to update RuleStockInfo");
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Error occurred in setRule", e);
            // 回滚事务
            throw new RuntimeException("Error occurred in setRule", e);
        }
    }
}
