package com.oms.inventory.service.impl.rule;

import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.service.IOmsChannelInventoryService;
import com.oms.inventory.service.IWmsInventoryService;
import com.oms.inventory.service.rule.AllocationStrategyService;
import com.oms.inventory.service.rule.IRuleStockChannelInfoService;
import com.oms.inventory.service.rule.IRuleStockStoreCodeInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@StrategyType("PRIORITY")
@Service
public class PriorityStrategyServiceImpl extends StrategyBaseServiceImpl implements AllocationStrategyService {

    @Override
    public Boolean allocate(RuleStockInfo rule) {
        log.info("PriorityStrategyServiceImpl.allocate, rule:{}", rule);

        List<String> storeCodes = this.getStoreCodesByRuleId(rule.getId());
        log.debug("storeCodes:{}", storeCodes);

        if (rule.getRuleRange() == 1) {
            processAllSkus(rule.getId(), storeCodes);
        } else {
            processSelectedSkus(rule.getId(), storeCodes);
        }

        return true;
    }

    private void processSelectedSkus(Long ruleId, List<String> storeCodes) {
        List<String> skuList = getSkuList(ruleId);
        log.debug("skuList:{}", skuList);
        skuList.forEach(sku -> processInventoryByStoreAndSku(ruleId, storeCodes, sku));
    }

    private void processAllSkus(Long ruleId, List<String> storeCodes) {
        int pageSize = 1000;
        int currentPage = 1;

        while (true) {
            List<String> skuList = this.getSkusByPage(currentPage, pageSize);
            if (skuList.isEmpty()) {
                break;
            }
            skuList.forEach(sku -> processInventoryByStoreAndSku(ruleId, storeCodes, sku));
            currentPage++;
        }
    }

    @Transactional
    public Boolean processInventoryByStoreAndSku(Long ruleId, List<String> storeCodes, String sku) {
        // 验证输入参数是否有效
        validateInputParameters(storeCodes, sku);
        try {
            // 从WMS系统中获取指定SKU的库存信息
            Map<String, Object> wmsInventory = wmsInventoryService.selectSkuTotalAvailable(storeCodes, sku);
            // 验证从WMS系统获取的库存信息是否有效
            validateWmsInventory(wmsInventory, storeCodes, sku);

            // 从库存信息中提取SKU序列号和总可用库存
            String skuSn = (String) wmsInventory.getOrDefault("sku_sn", null);
            BigDecimal totalAvailable = (BigDecimal) wmsInventory.getOrDefault("total_available", BigDecimal.ZERO);
            // 验证提取的SKU序列号和总可用库存是否有效
            validateInventoryFields(skuSn, totalAvailable, storeCodes, sku, wmsInventory);

            // 处理库存分配逻辑
            processAllocateInventory(ruleId, skuSn, totalAvailable);
            return true;
        }catch (Exception e) {
            // 记录处理过程中发生的异常信息
            log.error("处理库存时发生异常，storeCodes={}，sku={}，异常信息：{}", storeCodes, sku, e.getMessage(), e);
            return false;
        }
    }

    private void processAllocateInventory(Long ruleId, String skuSn, BigDecimal totalAvailable) {

    }
}
