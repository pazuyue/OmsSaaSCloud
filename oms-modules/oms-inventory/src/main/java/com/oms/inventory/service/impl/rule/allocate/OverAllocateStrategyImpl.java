package com.oms.inventory.service.impl.rule.allocate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.model.dto.rule.AllocationResult;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.service.IWmsInventoryService;
import com.oms.inventory.service.rule.AllocationStrategyService;
import com.oms.inventory.service.rule.IRuleStockStoreCodeInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@StrategyType("OVER_ALLOCATE")
@Component
public class OverAllocateStrategyImpl implements AllocationStrategyService {

    @Resource
    private IRuleStockStoreCodeInfoService ruleStockStoreCodeInfoService;

    @Resource
    private IWmsInventoryService wmsInventoryService;

    @Override
    public AllocationResult allocate(RuleStockInfo rule) {
        log.info("OverAllocateStrategyImpl.allocate");
        log.info("rule:{}", rule);

        // 获取 store_code 集合
        List<RuleStockStoreCodeInfo> storeCodeInfos = ruleStockStoreCodeInfoService.list(
                new LambdaQueryWrapper<RuleStockStoreCodeInfo>().eq(RuleStockStoreCodeInfo::getRuleId, rule.getId())
        );
        List<String> storeCodes = Arrays.asList(storeCodeInfos.stream().map(RuleStockStoreCodeInfo::getStoreCode).toArray(String[]::new));
        log.debug("storeCodes:{}", storeCodes);
        if (rule.getRuleRange() == 1) {
            // 全部商品
            paginateAndProcessInventory(storeCodes);
        } else {
            // 部分商品
        }

        return null;
    }

    private void paginateAndProcessInventory(List<String> storeCodes) {
        int pageSize = 1000;
        int currentPage = 1;
        while (true) {
            // 分页查询
            List<Map<String, Object>> wmsInventories = wmsInventoryService.selectSkuTotalAvailable(storeCodes,null,currentPage,pageSize);
            log.debug("分页查询结果：{}", wmsInventories);
            if (wmsInventories.isEmpty()) {
                // 如果没有更多数据，则退出循环
                break;
            }
            log.debug("分页查询结果1：{}", wmsInventories);
            // 使用查询结果进行处理（例如分配库存）
            for (Map<String, Object> wmsInventory : wmsInventories) {
                String skuSn = (String) wmsInventory.get("sku_sn");
                BigDecimal totalAvailable = (BigDecimal) wmsInventory.get("total_available");
                processAllocateInventory(skuSn,totalAvailable);
            }
            // 更新当前页码
            currentPage++;
        }
    }

    private Boolean processAllocateInventory( String skuSn, BigDecimal totalAvailable) {
        // 获取库存信息
        try {
            log.debug("skuSn={} totalAvailable={}", skuSn, totalAvailable);
        } catch (Exception e) {
            log.error("processAllocateInventory error:{}", e);
            return false;
        }
        return true;
    }
}
