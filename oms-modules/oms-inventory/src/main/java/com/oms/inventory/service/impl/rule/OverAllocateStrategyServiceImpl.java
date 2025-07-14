package com.oms.inventory.service.impl.rule;

import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.mapper.OmsInventoryMapper;
import com.oms.inventory.model.entity.OmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.service.rule.AllocationStrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;

/**
 * 超额分配策略服务实现类
 * 实现超额分配策略，允许渠道分配超过实际库存的数量
 * 适用于预售、预订等业务场景
 *
 * @author system
 * @since 2024
 */
@Slf4j
@StrategyType("OVER_ALLOCATE")
@Service
public class OverAllocateStrategyServiceImpl extends StrategyBaseServiceImpl implements AllocationStrategyService {

    /**
     * 执行超额分配策略
     * 根据规则范围类型选择处理全部商品或指定商品
     *
     * @param rule 分货规则信息
     * @return Boolean 分配成功返回true
     */
    @Override
    public Boolean allocate(RuleStockInfo rule) {
        log.info("开始执行超额分配策略，规则ID: {}, 规则名称: {}", rule.getId(), rule.getRuleName());

        List<String> storeCodes = getStoreCodesByRuleId(rule.getId());
        log.debug("获取仓库代码列表: {}", storeCodes);

        // 根据规则范围类型选择处理方式
        if (rule.getRuleRange() == ALL_SKU_RANGE) {
            log.info("处理全部商品库存分配");
            processAllSkus(rule.getId(), storeCodes, this::processInventoryByStoreAndSku);
        } else {
            log.info("处理指定商品库存分配");
            processSelectedSkus(rule.getId(), this::processInventoryByStoreAndSku);
        }

        log.info("超额分配策略执行完成，规则ID: {}", rule.getId());
        return true;
    }











    /**
     * 处理单个SKU在指定仓库的库存分配
     *
     * @param ruleId 规则ID
     * @param sku SKU编码
     */
    private void processInventoryByStoreAndSku(Long ruleId, String sku) {
        try {
            log.debug("开始处理SKU: {} 的库存分配", sku);

            // 获取仓库代码列表
            List<String> storeCodes = getStoreCodesByRuleId(ruleId);

            // 获取WMS库存信息
            Map<String, Object> wmsInventory = getWmsInventoryInfo(storeCodes, sku);

            // 提取库存信息
            InventoryInfo inventoryInfo = extractInventoryInfo(wmsInventory, storeCodes, sku);

            // 处理库存分配
            processAllocateInventory(ruleId, inventoryInfo.getSkuSn(), inventoryInfo.getTotalAvailable());

            log.debug("SKU: {} 库存分配处理完成", sku);

        } catch (Exception e) {
            log.error("处理SKU: {} 库存分配时发生异常", sku, e);
            // 根据业务需求决定是否继续处理其他SKU或抛出异常
            // 这里选择记录错误但继续处理其他SKU
        }
    }

    /**
     * 处理库存分配的核心逻辑
     *
     * @param ruleId 规则ID
     * @param skuSn SKU序列号
     * @param totalAvailable 总可用库存
     */
    private void processAllocateInventory(Long ruleId, String skuSn, BigDecimal totalAvailable) {
        try {
            // 1. 初始化分配上下文
            BaseAllocationContext context = initializeAllocationContext(ruleId, skuSn, totalAvailable);

            // 2. 执行渠道分配
            BigDecimal totalAllocatedAmount = executeChannelAllocation(context);

            // 3. 处理WMS库存锁定（如果是锁库单分货）
            handleWmsInventoryLocking(context, totalAllocatedAmount);

            log.info("超分策略库存分配完成，SKU: {}, 总分配数量: {}", skuSn, totalAllocatedAmount);

        } catch (Exception e) {
            log.error("超分策略库存分配异常，SKU: {}", skuSn, e);
            throw new RuntimeException("超分策略库存分配失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行渠道分配 - 超额分配策略实现
     * 超额分配策略：允许各渠道分配的总量超过实际库存
     */
    protected BigDecimal executeChannelAllocation(BaseAllocationContext context) {
        BigDecimal totalAllocatedAmount = BigDecimal.ZERO;

        log.debug("开始执行超额分配策略渠道分配，渠道数量: {}", context.getChannelInfoList().size());

        for (RuleStockChannelInfo channelInfo : context.getChannelInfoList()) {
            // 超额分配策略：直接按照渠道配置的数量进行分配，不受库存限制
            BigDecimal channelAllocationAmount = calculateAvailableStock(channelInfo, context.getTotalAvailable());

            // 执行渠道库存分配
            boolean allocationResult = allocateToChannel(context, channelInfo, channelAllocationAmount);

            // 累计成功分配的数量
            if (allocationResult) {
                totalAllocatedAmount = totalAllocatedAmount.add(channelAllocationAmount);
                log.debug("超额分配策略：渠道 {} 分配成功，分配量: {}, 累计分配量: {}",
                        channelInfo.getChannelId(), channelAllocationAmount, totalAllocatedAmount);
            }
        }

        log.info("超额分配策略渠道分配完成，SKU: {}, 总分配量: {}, 原始库存: {}",
                context.getSkuSn(), totalAllocatedAmount, context.getTotalAvailable());
        return totalAllocatedAmount;
    }





}
