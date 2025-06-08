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

    /** 分页查询默认页大小 */
    private static final int DEFAULT_PAGE_SIZE = 1000;
    
    /** SKU范围类型：全部商品 */
    private static final int ALL_SKU_RANGE = 1;

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
            processAllSkus(rule.getId(), storeCodes);
        } else {
            log.info("处理指定商品库存分配");
            processSelectedSkus(rule.getId(), storeCodes);
        }

        log.info("超额分配策略执行完成，规则ID: {}", rule.getId());
        return true;
    }

    /**
     * 处理全部商品的库存分配
     * 采用分页方式处理，避免一次性加载过多数据导致内存溢出
     * 
     * @param ruleId 规则ID
     * @param storeCodes 仓库代码列表
     */
    private void processAllSkus(Long ruleId, List<String> storeCodes) {
        int currentPage = 1;
        int processedCount = 0;

        log.info("开始分页处理全部商品，页大小: {}", DEFAULT_PAGE_SIZE);
        
        while (true) {
            List<String> skuList = this.getSkusByPage(currentPage, DEFAULT_PAGE_SIZE);
            if (skuList.isEmpty()) {
                log.info("全部商品处理完成，共处理 {} 个SKU", processedCount);
                break;
            }
            
            log.debug("处理第 {} 页，SKU数量: {}", currentPage, skuList.size());
            skuList.forEach(sku -> processInventoryByStoreAndSku(ruleId, storeCodes, sku));
            
            processedCount += skuList.size();
            currentPage++;
        }
    }

    /**
     * 处理指定商品的库存分配
     * 根据规则ID获取指定的SKU列表进行处理
     * 
     * @param ruleId 规则ID
     * @param storeCodes 仓库代码列表
     */
    private void processSelectedSkus(Long ruleId, List<String> storeCodes) {
        List<String> skuList = getSkuList(ruleId);
        log.info("开始处理指定商品，SKU数量: {}", skuList.size());
        log.debug("指定商品SKU列表: {}", skuList);
        
        skuList.forEach(sku -> processInventoryByStoreAndSku(ruleId, storeCodes, sku));
        log.info("指定商品处理完成");
    }

    /**
     * 根据仓库和SKU处理库存信息
     * 超额分配策略的核心处理方法，负责获取WMS库存信息并执行分配逻辑
     * 
     * @param ruleId 库存分配规则的ID
     * @param storeCodes 仓库代码列表
     * @param sku 商品SKU编码
     * @return Boolean 处理结果，true表示成功，false表示失败
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean processInventoryByStoreAndSku(Long ruleId, List<String> storeCodes, String sku) {
        log.debug("开始处理SKU库存分配，规则ID: {}, SKU: {}, 仓库: {}", ruleId, sku, storeCodes);
        
        // 1. 验证输入参数
        validateInputParameters(storeCodes, sku);

        try {
            // 2. 获取WMS库存信息
            Map<String, Object> wmsInventory = getWmsInventoryInfo(storeCodes, sku);
            
            // 3. 提取并验证库存字段
            InventoryInfo inventoryInfo = extractInventoryInfo(wmsInventory, storeCodes, sku);

            // 4. 执行库存分配逻辑
            processAllocateInventory(ruleId, inventoryInfo.getSkuSn(), inventoryInfo.getTotalAvailable());
            
            log.debug("SKU库存分配处理成功，SKU: {}", sku);
            return true;
        } catch (Exception e) {
            log.error("处理SKU库存分配时发生异常，规则ID: {}, SKU: {}, 仓库: {}, 异常: {}", 
                    ruleId, sku, storeCodes, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取WMS库存信息
     */
    private Map<String, Object> getWmsInventoryInfo(List<String> storeCodes, String sku) {
        Map<String, Object> wmsInventory = wmsInventoryService.selectSkuTotalAvailable(storeCodes, sku);
        validateWmsInventory(wmsInventory, storeCodes, sku);
        return wmsInventory;
    }
    
    /**
     * 提取库存信息
     */
    private InventoryInfo extractInventoryInfo(Map<String, Object> wmsInventory, List<String> storeCodes, String sku) {
        String skuSn = (String) wmsInventory.getOrDefault("sku_sn", null);
        BigDecimal totalAvailable = (BigDecimal) wmsInventory.getOrDefault("total_available", BigDecimal.ZERO);
        
        validateInventoryFields(skuSn, totalAvailable, storeCodes, sku, wmsInventory);
        
        return new InventoryInfo(skuSn, totalAvailable);
    }
    
    /**
     * 库存信息内部类
     */
    private static class InventoryInfo {
        private final String skuSn;
        private final BigDecimal totalAvailable;
        
        public InventoryInfo(String skuSn, BigDecimal totalAvailable) {
            this.skuSn = skuSn;
            this.totalAvailable = totalAvailable;
        }
        
        public String getSkuSn() { return skuSn; }
        public BigDecimal getTotalAvailable() { return totalAvailable; }
    }

    /**
     * 处理库存分配逻辑
     * 超额分配策略：允许各渠道分配的总量超过实际库存
     * 适用于预售、预订等可以超卖的业务场景
     * 
     * @param ruleId 规则ID
     * @param skuSn SKU序列号
     * @param totalAvailable 总可用库存量
     * @return Boolean 分配成功返回true
     */
    private Boolean processAllocateInventory(Long ruleId, String skuSn, BigDecimal totalAvailable) {
        log.debug("开始执行超额分配逻辑，SKU: {}, 总可用库存: {}", skuSn, totalAvailable);
        
        try {
            // 1. 初始化分配上下文
            AllocationContext context = initializeAllocationContext(ruleId, skuSn, totalAvailable);
            
            // 2. 执行渠道分配
            BigDecimal totalAllocatedAmount = executeChannelAllocation(context);
            
            // 3. 处理WMS库存锁定（如果需要）
            handleWmsInventoryLocking(context, totalAllocatedAmount);
            
            log.debug("超额分配逻辑执行完成，SKU: {}, 总分配量: {}", skuSn, totalAllocatedAmount);
            return true;
            
        } catch (Exception e) {
            log.error("执行超额分配逻辑时发生异常，SKU: {}, 异常: {}", skuSn, e.getMessage(), e);
            throw new RuntimeException("超额分配逻辑执行失败", e);
        }
    }
    
    /**
     * 初始化分配上下文
     */
    private AllocationContext initializeAllocationContext(Long ruleId, String skuSn, BigDecimal totalAvailable) {
        boolean isLockAllocation = isLockAllocation(ruleId.toString());
        List<String> storeCodes = getStoreCodesByRuleId(ruleId);
        List<RuleStockChannelInfo> channelInfoList = getRuleStockChannelInfoList(ruleId);
        
        return new AllocationContext(ruleId, skuSn, totalAvailable, isLockAllocation, storeCodes, channelInfoList);
    }
    
    /**
     * 执行渠道分配
     */
    private BigDecimal executeChannelAllocation(AllocationContext context) {
        BigDecimal totalAllocatedAmount = BigDecimal.ZERO;
        
        log.debug("开始执行渠道分配，渠道数量: {}", context.getChannelInfoList().size());
        
        for (RuleStockChannelInfo channelInfo : context.getChannelInfoList()) {
            // 验证渠道信息
            validateChannelInfo(channelInfo, context.getRuleId(), context.getSkuSn(), context.getTotalAvailable());
            
            // 计算渠道分配量（超额分配策略：基于总库存计算，不考虑剩余库存限制）
            BigDecimal channelAllocationAmount = calculateAvailableStock(channelInfo, context.getTotalAvailable());
            
            // 执行渠道库存分配
            boolean allocationResult = allocateToChannel(context, channelInfo, channelAllocationAmount);
            
            // 累计成功分配的数量
            if (allocationResult) {
                totalAllocatedAmount = totalAllocatedAmount.add(channelAllocationAmount);
                log.debug("渠道 {} 分配成功，分配量: {}, 累计分配量: {}", 
                        channelInfo.getChannelId(), channelAllocationAmount, totalAllocatedAmount);
            }
        }
        
        log.info("渠道分配完成，SKU: {}, 总分配量: {}, 原始库存: {}", 
                context.getSkuSn(), totalAllocatedAmount, context.getTotalAvailable());
        return totalAllocatedAmount;
    }
    
    /**
     * 执行单个渠道的库存分配
     */
    private boolean allocateToChannel(AllocationContext context, RuleStockChannelInfo channelInfo, BigDecimal amount) {
        return omsChannelInventoryService.allocationInventory(
                context.getRuleId().toString(),
                channelInfo.getChannelId(),
                context.getSkuSn(),
                channelInfo.getCompanyCode(),
                amount
        );
    }
    
    /**
     * 处理WMS库存锁定
     */
    private void handleWmsInventoryLocking(AllocationContext context, BigDecimal totalAllocatedAmount) {
        if (!context.isLockAllocation() || totalAllocatedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        log.info("锁库单分货：开始处理WMS库存锁定，SKU: {}, 锁定数量: {}, 仓库: {}", 
                context.getSkuSn(), totalAllocatedAmount, context.getStoreCodes());
        
        Boolean lockResult = wmsInventoryService.lockInventory(
                context.getStoreCodes(), context.getSkuSn(), totalAllocatedAmount);
        
        if (!lockResult) {
            String errorMsg = String.format("WMS库存锁定失败，SKU: %s, 锁定数量: %s", 
                    context.getSkuSn(), totalAllocatedAmount);
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        log.info("锁库单分货：WMS库存锁定成功，SKU: {}, 锁定数量: {}", 
                context.getSkuSn(), totalAllocatedAmount);
    }
    
    /**
     * 分配上下文内部类
     * 封装分配过程中需要的所有上下文信息
     */
    private static class AllocationContext {
        private final Long ruleId;
        private final String skuSn;
        private final BigDecimal totalAvailable;
        private final boolean isLockAllocation;
        private final List<String> storeCodes;
        private final List<RuleStockChannelInfo> channelInfoList;
        
        public AllocationContext(Long ruleId, String skuSn, BigDecimal totalAvailable, 
                               boolean isLockAllocation, List<String> storeCodes, 
                               List<RuleStockChannelInfo> channelInfoList) {
            this.ruleId = ruleId;
            this.skuSn = skuSn;
            this.totalAvailable = totalAvailable;
            this.isLockAllocation = isLockAllocation;
            this.storeCodes = storeCodes;
            this.channelInfoList = channelInfoList;
        }
        
        // Getters
        public Long getRuleId() { return ruleId; }
        public String getSkuSn() { return skuSn; }
        public BigDecimal getTotalAvailable() { return totalAvailable; }
        public boolean isLockAllocation() { return isLockAllocation; }
        public List<String> getStoreCodes() { return storeCodes; }
        public List<RuleStockChannelInfo> getChannelInfoList() { return channelInfoList; }
    }

}
