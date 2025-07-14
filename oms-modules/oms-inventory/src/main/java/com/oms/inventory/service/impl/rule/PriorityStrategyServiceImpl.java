package com.oms.inventory.service.impl.rule;

import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.mapper.OmsInventoryMapper;
import com.oms.inventory.model.entity.OmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
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

/**
 * 优先级分配策略服务实现类
 * 实现优先级分配策略，按照渠道优先级顺序分配库存
 * 优先级高的渠道优先获得库存，剩余库存再分配给下一优先级渠道
 *
 * @author system
 * @since 2024
 */
@Slf4j
@StrategyType("PRIORITY")
@Service
public class PriorityStrategyServiceImpl extends StrategyBaseServiceImpl implements AllocationStrategyService {



    /**
     * 执行优先级分配策略
     * 根据规则范围类型选择处理全部商品或指定商品
     *
     * @param rule 分货规则信息
     * @return Boolean 分配成功返回true
     */
    @Override
    public Boolean allocate(RuleStockInfo rule) {
        log.info("开始执行优先级分配策略，规则ID: {}, 规则名称: {}", rule.getId(), rule.getRuleName());

        List<String> storeCodes = this.getStoreCodesByRuleId(rule.getId());
        log.debug("获取仓库代码列表: {}", storeCodes);

        // 根据规则范围类型选择处理方式
        if (rule.getRuleRange() == 1) {
            log.info("处理全部商品库存分配");
            processAllSkus(rule.getId(), storeCodes, this::processInventoryByStoreAndSku);
        } else {
            log.info("处理指定商品库存分配");
            processSelectedSkus(rule.getId(), this::processInventoryByStoreAndSku);
        }

        log.info("优先级分配策略执行完成，规则ID: {}", rule.getId());
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
    @Transactional(rollbackFor = Exception.class)
    private void processAllocateInventory(Long ruleId, String skuSn, BigDecimal totalAvailable) {
        try {
            // 初始化分配上下文
            BaseAllocationContext context = initializeAllocationContext(ruleId, skuSn, totalAvailable);

            // 执行渠道分配
            BigDecimal totalAllocatedAmount = executePriorityChannelAllocation(context);

            // 处理WMS库存锁定（如果是锁库单分货）
            handleWmsInventoryLocking(context, totalAllocatedAmount);

            log.info("优先级策略库存分配完成，SKU: {}, 总分配数量: {}", skuSn, totalAllocatedAmount);

        } catch (Exception e) {
            log.error("优先级策略库存分配异常，SKU: {}", skuSn, e);
            throw new RuntimeException("优先级策略库存分配失败: " + e.getMessage(), e);
        }
    }



    /**
     * 执行优先级渠道分配
     */
    private BigDecimal executePriorityChannelAllocation(BaseAllocationContext context) {
        BigDecimal remainingAvailable = context.getTotalAvailable(); // 剩余可分配库存
        BigDecimal totalAllocatedAmount = BigDecimal.ZERO;

        log.debug("开始执行优先级渠道分配，渠道数量: {}", context.getChannelInfoList().size());

        for (RuleStockChannelInfo channelInfo : context.getChannelInfoList()) {
            // 如果没有剩余库存，停止分配
            if (remainingAvailable.compareTo(BigDecimal.ZERO) <= 0) {
                log.debug("剩余库存为0，停止分配");
                break;
            }

            // 验证渠道信息
            validateChannelInfo(channelInfo, context.getRuleId(), context.getSkuSn(), context.getTotalAvailable());

            // 计算渠道期望分配量
            BigDecimal expectedAllocationAmount = calculateAvailableStock(channelInfo, context.getTotalAvailable());

            // 确定实际分配量（不能超过剩余库存）
            BigDecimal actualAllocationAmount = determineActualAllocationAmount(remainingAvailable, expectedAllocationAmount);

            // 执行渠道库存分配
            BigDecimal allocatedAmount = allocateToChannelWithPriority(context, channelInfo, actualAllocationAmount);

            // 更新剩余库存和累计分配量
            remainingAvailable = remainingAvailable.subtract(allocatedAmount);
            totalAllocatedAmount = totalAllocatedAmount.add(allocatedAmount);

            log.debug("渠道 {} 分配完成，期望: {}, 实际: {}, 剩余库存: {}, 累计分配: {}",
                    channelInfo.getChannelId(), expectedAllocationAmount, allocatedAmount,
                    remainingAvailable, totalAllocatedAmount);
        }

        log.info("优先级渠道分配完成，SKU: {}, 总分配量: {}, 剩余库存: {}",
                context.getSkuSn(), totalAllocatedAmount, remainingAvailable);
        return totalAllocatedAmount;
    }

    /**
     * 确定实际分配量
     * 优先级分配策略：实际分配量不能超过剩余库存
     */
    private BigDecimal determineActualAllocationAmount(BigDecimal remainingAvailable, BigDecimal expectedAmount) {
        return remainingAvailable.compareTo(expectedAmount) >= 0 ? expectedAmount : remainingAvailable;
    }

    /**
     * 执行单个渠道的优先级库存分配
     */
    private BigDecimal allocateToChannelWithPriority(BaseAllocationContext context,
                                                      RuleStockChannelInfo channelInfo,
                                                      BigDecimal amount) {
        boolean success = allocateToChannel(context, channelInfo, amount);
        return success ? amount : BigDecimal.ZERO;
    }




}


