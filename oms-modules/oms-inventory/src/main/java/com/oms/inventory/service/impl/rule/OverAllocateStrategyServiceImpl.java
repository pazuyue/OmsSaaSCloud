package com.oms.inventory.service.impl.rule;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.service.IOmsChannelInventoryService;
import com.oms.inventory.service.IWmsInventoryService;
import com.oms.inventory.service.rule.AllocationStrategyService;
import com.oms.inventory.service.rule.IRuleStockChannelInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@StrategyType("OVER_ALLOCATE")
@Service
public class OverAllocateStrategyServiceImpl extends StrategyBaseServiceImpl implements AllocationStrategyService {

    @Override
    public Boolean allocate(RuleStockInfo rule) {
        log.info("OverAllocateStrategyImpl.allocate, rule:{}", rule);

        List<String> storeCodes = getStoreCodesByRuleId(rule.getId());
        log.debug("storeCodes:{}", storeCodes);

        if (rule.getRuleRange() == 1) {
            processAllSkus(rule.getId(), storeCodes);
        } else {
            processSelectedSkus(rule.getId(), storeCodes);
        }

        return true;
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



    private void processSelectedSkus(Long ruleId, List<String> storeCodes) {
        List<String> skuList = getSkuList(ruleId);
        log.debug("skuList:{}", skuList);
        skuList.forEach(sku -> processInventoryByStoreAndSku(ruleId, storeCodes, sku));
    }

    /**
     * 根据门店和SKU处理库存信息。
     * 该函数首先验证输入参数，然后从WMS系统中获取指定SKU的库存信息，并进行验证。
     * 如果库存信息有效，则进一步处理库存分配，并返回处理结果。
     *
     * @param ruleId 库存分配规则的ID，用于处理库存分配逻辑
     * @param storeCodes 仓库代码列表，指定需要处理库存的仓库
     * @param sku 商品SKU，指定需要处理库存的商品
     * @return Boolean 返回处理结果，true表示处理成功，false表示处理失败
     */
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
        } catch (Exception e) {
            // 记录处理过程中发生的异常信息
            log.error("处理库存时发生异常，storeCodes={}，sku={}，异常信息：{}", storeCodes, sku, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 处理库存分配的逻辑。
     *
     * 该函数根据给定的规则ID、SKU编号和总可用库存，遍历所有相关的库存渠道信息，
     * 验证每个渠道的库存信息，并计算可用库存，最后调用服务进行库存分配。
     *
     * @param ruleId 规则ID，用于获取相关的库存渠道信息
     * @param skuSn SKU编号，标识需要分配库存的商品
     * @param totalAvailable 总可用库存量，用于计算每个渠道的分配量
     * @return 如果所有渠道的库存分配成功，则返回true；如果过程中发生异常，则抛出RuntimeException
     */
    private Boolean processAllocateInventory(Long ruleId, String skuSn, BigDecimal totalAvailable) {
        try {
            // 记录调试信息，输出SKU编号和总可用库存
            log.debug("skuSn={} totalAvailable={}", skuSn, totalAvailable);

            // 获取与规则ID相关的所有库存渠道信息
            List<RuleStockChannelInfo> ruleStockChannelInfoList = getRuleStockChannelInfoList(ruleId);

            // 遍历每个库存渠道信息，进行库存分配
            for (RuleStockChannelInfo ruleStockChannelInfo : ruleStockChannelInfoList) {
                // 验证当前渠道的库存信息是否有效
                validateChannelInfo(ruleStockChannelInfo, ruleId, skuSn, totalAvailable);

                // 计算当前渠道的可用库存
                BigDecimal availableStock = calculateAvailableStock(ruleStockChannelInfo, totalAvailable);
                log.debug("availableStock:{}", availableStock);

                // 调用服务进行库存分配
                omsChannelInventoryService.allocationInventory(ruleId.toString(),ruleStockChannelInfo.getChannelId(), skuSn, ruleStockChannelInfo.getCompanyCode(), availableStock);
            }

            // 所有渠道的库存分配成功，返回true
            return true;
        } catch (Exception e) {
            // 记录错误日志，并抛出运行时异常
            log.error("processAllocateInventory error:{}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
