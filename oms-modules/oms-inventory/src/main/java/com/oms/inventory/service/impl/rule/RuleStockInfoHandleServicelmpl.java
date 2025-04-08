package com.oms.inventory.service.impl.rule;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.oms.inventory.factory.StrategyFactory;
import com.oms.inventory.model.dto.AllocationRuleDto;
import com.oms.inventory.model.dto.rule.RuleDetailsInfoDto;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.model.enums.RuleStatus;
import com.oms.inventory.service.impl.WmsInventoryServiceImpl;
import com.oms.inventory.service.rule.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
public class RuleStockInfoHandleServicelmpl implements IRuleStockInfoHandleService {

    private static final int ALL_SKU_LIST = 1;
    private static final int APPOINT_SKU_LIST = 2;
    @Resource
    private IRuleStockInfoService ruleStockInfoService;
    @Resource
    private IRuleStockStoreCodeInfoService ruleStockStoreCodeInfoService;
    @Resource
    private IRuleStockChannelInfoService ruleStockChannelInfoService;

    @Resource
    private WmsInventoryServiceImpl wmsInventoryService;

    @Resource
    private StrategyFactory strategyFactory;

    @Override
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
                        ruleStockChannelInfo.setChannelName(item.getChannelName());
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
            one.setRuleMode(dto.getPriorityType());
            boolean saveResult = ruleStockChannelInfoService.saveBatch(list);
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

    @Override
    public RuleDetailsInfoDto getInfoDetails(Long id,String companyCode) {
        QueryWrapper<RuleStockStoreCodeInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("rule_id",id);
        List<RuleStockStoreCodeInfo> storeCodeList = ruleStockStoreCodeInfoService.list(queryWrapper);
        List<RuleStockChannelInfo> channelList = ruleStockChannelInfoService.list(new QueryWrapper<RuleStockChannelInfo>().eq("rule_id", id));
        RuleDetailsInfoDto dto = new RuleDetailsInfoDto();
        dto.setStoreCodeInfoList(storeCodeList);
        dto.setChannelInfoList(channelList);
        return dto;
    }

    @Override
    public Boolean toExamine(Long id, String companyCode) {
        RuleStockInfo one = ruleStockInfoService.getById(id);
        if (ObjectUtil.isEmpty(one)) {
            throw new RuntimeException("分货单不存在");
        }
        if (one.getStatus() != RuleStatus.PENDING_REVIEW){
            throw new RuntimeException("分货单状态不正确");
        }
        switch (one.getRuleType()){
            case 1: //日常分货
            case 3: //锁库时分货
                one.setStatus(RuleStatus.PENDING_EXECUTION); // 更新为待执行
                ruleStockInfoService.updateRuleStockInfo(one);
                return true;
            case 2: //一次性分货
                one.setStatus(RuleStatus.EXECUTING); // 更新为执行中
                ruleStockInfoService.updateRuleStockInfo(one);
                return handleAllocate(one);
            default:
                throw new RuntimeException("分货单类型不正确");

        }
    }

    private Boolean handleAllocate(RuleStockInfo rule){
        AllocationStrategyService allocationStrategyService = strategyFactory.getStrategy(rule.getRuleMode().getRemark());
        allocationStrategyService.allocate(rule);
        return true;
    }

    /**
     * 获取商品范围
     * @param ruleRange
     * @return
     */
    public List<String> getSkuList(int ruleRange){
        switch (ruleRange){
            case ALL_SKU_LIST: //全部商品
                List<WmsInventory> wmsInventories = wmsInventoryService.selectWmsInventoryListBySkuSn(new WmsInventory());
                return wmsInventories.stream().map(WmsInventory::getSkuSn).collect(Collectors.toList());
            case APPOINT_SKU_LIST:
                return Collections.emptyList();
        }
        return Collections.emptyList();
    }


}
