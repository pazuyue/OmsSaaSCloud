package com.oms.inventory.service.impl.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.mapper.OmsInventoryMapper;
import com.oms.inventory.model.entity.OmsInventory;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockGoodsInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.service.IOmsChannelInventoryService;
import com.oms.inventory.service.IWmsInventoryService;
import com.oms.inventory.service.rule.IRuleStockChannelInfoService;
import com.oms.inventory.service.rule.IRuleStockInfoService;
import com.oms.inventory.service.rule.IRuleStockStoreCodeInfoService;
import com.oms.inventory.service.rule.RuleStockGoodsInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StrategyBaseServiceImpl {

    protected static final BigDecimal ONE_HUNDRED = new BigDecimal(100);

    @Resource
    protected IRuleStockStoreCodeInfoService ruleStockStoreCodeInfoService;
    @Resource
    protected IWmsInventoryService wmsInventoryService;
    @Resource
    protected IRuleStockChannelInfoService ruleStockChannelInfoService;
    @Resource
    protected IOmsChannelInventoryService omsChannelInventoryService;
    @Resource
    protected IRuleStockInfoService ruleStockInfoService;
    @Resource
    protected OmsInventoryMapper omsInventoryMapper;
    @Resource
    protected RuleStockGoodsInfoService ruleStockGoodsInfoService;

    /** 分页查询默认页大小 */
    protected static final int DEFAULT_PAGE_SIZE = 1000;

    /** SKU范围类型：全部商品 */
    protected static final int ALL_SKU_RANGE = 1;

    protected List<String> getStoreCodesByRuleId(Long ruleId) {
        return ruleStockStoreCodeInfoService.list(
                        new LambdaQueryWrapper<RuleStockStoreCodeInfo>().eq(RuleStockStoreCodeInfo::getRuleId, ruleId)
                ).stream()
                .map(RuleStockStoreCodeInfo::getStoreCode)
                .collect(Collectors.toList());
    }

    protected List<String> getSkusByPage(int page, int pageSize) {
        PageHelper.startPage(page, pageSize);
        return wmsInventoryService.list(new QueryWrapper<WmsInventory>().select("sku_sn"))
                .stream()
                .map(WmsInventory::getSkuSn)
                .distinct() // 去重
                .collect(Collectors.toList());
    }

    /**
     * 根据指定的十进制处理类型对可用库存进行舍入处理。
     *
     * @param availableStock 需要处理的可用库存值，类型为 BigDecimal。
     * @param decimalHandleType 十进制处理类型，类型为 Integer。具体取值如下：
     *                          1 - 向下舍入（RoundingMode.DOWN）；
     *                          2 - 向上舍入（RoundingMode.UP）；
     *                          其他值 - 四舍五入（RoundingMode.HALF_UP）。
     * @return 返回经过舍入处理后的 BigDecimal 值，小数位数为 0。
     */
    protected BigDecimal applyDecimalHandling(BigDecimal availableStock, Integer decimalHandleType) {
        switch (decimalHandleType) {
            case 1:
                // 向下舍入到整数
                return availableStock.setScale(0, RoundingMode.DOWN);
            case 2:
                // 向上舍入到整数
                return availableStock.setScale(0, RoundingMode.UP);
            default:
                // 默认四舍五入到整数
                return availableStock.setScale(0, RoundingMode.HALF_UP);
        }
    }

    protected void validateInputParameters(List<String> storeCodes, String sku) {
        if (storeCodes == null || storeCodes.isEmpty() || sku == null || sku.isEmpty()) {
            log.warn("输入参数无效：storeCodes={}，sku={}", storeCodes, sku);
            throw new IllegalArgumentException("输入参数无效");
        }
    }

    protected void validateWmsInventory(Map<String, Object> wmsInventory, List<String> storeCodes, String sku) {
        if (wmsInventory == null || wmsInventory.isEmpty()) {
            log.debug("未查询到库存信息，storeCodes={}，sku={}", storeCodes, sku);
            throw new RuntimeException("未查询到库存信息");
        }
    }

    protected void validateInventoryFields(String skuSn, BigDecimal totalAvailable, List<String> storeCodes, String sku, Map<String, Object> wmsInventory) {
        if (skuSn == null || totalAvailable == null) {
            log.warn("库存信息缺失关键字段，storeCodes={}，sku={}，结果：{}", storeCodes, sku, wmsInventory);
            throw new RuntimeException("库存信息缺失关键字段");
        }
    }

    protected List<RuleStockChannelInfo> getRuleStockChannelInfoList(Long ruleId) {
        List<RuleStockChannelInfo> ruleStockChannelInfoList = ruleStockChannelInfoService.listByMap(Collections.singletonMap("rule_id", ruleId));
        if (ruleStockChannelInfoList.isEmpty()) {
            log.warn("未查询到渠道信息，ruleId={}", ruleId);
            throw new RuntimeException("未查询到渠道信息");
        }
        return ruleStockChannelInfoList;
    }

    protected void validateChannelInfo(RuleStockChannelInfo ruleStockChannelInfo, Long ruleId, String skuSn, BigDecimal totalAvailable) {
        if (totalAvailable.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("库存不足，skuSn={} totalAvailable={}", skuSn, totalAvailable);
            throw new RuntimeException("库存不足");
        }
        if (ruleStockChannelInfo.getChannelId() == null) {
            log.warn("渠道信息缺失关键字段，ruleId={}", ruleId);
            throw new RuntimeException("渠道信息缺失关键字段");
        }
    }

    protected List<String> getSkuList(Long ruleId) {
        return ruleStockGoodsInfoService.list(
                new LambdaQueryWrapper<RuleStockGoodsInfo>()
                        .eq(RuleStockGoodsInfo::getRuleId, ruleId)
        ).stream()
                .map(RuleStockGoodsInfo::getSkuSn)
                .collect(Collectors.toList());
    }

    protected BigDecimal calculateAvailableStock(RuleStockChannelInfo ruleStockChannelInfo, BigDecimal totalAvailable) {
        BigDecimal percentage = ruleStockChannelInfo.getPercentage();
        BigDecimal availableStock = ruleStockChannelInfo.getRuleType() == 1
                ? totalAvailable.multiply(percentage).divide(ONE_HUNDRED)
                : totalAvailable.compareTo(percentage) > 0 ? percentage : totalAvailable;
        return applyDecimalHandling(availableStock, ruleStockChannelInfo.getDecimalHandleType());
    }

    /**
     * 判断是否为锁库单分货
     * @param relationSn 关联单号（规则ID）
     * @return true-锁库单分货，false-非锁库单分货
     */
    protected boolean isLockAllocation(String relationSn) {
        try {
            // 尝试将relationSn转换为Long类型的规则ID
            Long ruleId = Long.parseLong(relationSn);
            RuleStockInfo ruleStockInfo = ruleStockInfoService.selectRuleStockInfoById(ruleId);

            // 分货类型是否为2（锁库时分货）
            return ruleStockInfo != null && ruleStockInfo.getAllocationType() != null && ruleStockInfo.getAllocationType() == 2;
        } catch (NumberFormatException e) {
            // 如果relationSn不是数字，则不是规则ID，返回false
            log.debug("relationSn is not a valid rule ID: {}", relationSn);
            return false;
        } catch (Exception e) {
            log.error("Error checking lock allocation for relationSn: {}", relationSn, e);
            return false;
        }
    }

    /**
     * 库存信息内部类
     * 封装SKU库存的基本信息
     */
    protected static class InventoryInfo {
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
     * 通用分配上下文基类
     * 封装分配过程中需要的所有上下文信息
     */
    protected static class BaseAllocationContext {
        private final Long ruleId;
        private final String skuSn;
        private final BigDecimal totalAvailable;
        private final boolean isLockAllocation;
        private final List<String> storeCodes;
        private final List<RuleStockChannelInfo> channelInfoList;

        public BaseAllocationContext(Long ruleId, String skuSn, BigDecimal totalAvailable,
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

    /**
     * 获取WMS库存信息
     * @param storeCodes 仓库代码列表
     * @param sku SKU编码
     * @return WMS库存信息
     */
    protected Map<String, Object> getWmsInventoryInfo(List<String> storeCodes, String sku) {
        Map<String, Object> wmsInventory = wmsInventoryService.selectSkuTotalAvailable(storeCodes, sku);
        validateWmsInventory(wmsInventory, storeCodes, sku);
        return wmsInventory;
    }

    /**
     * 提取库存信息
     * @param wmsInventory WMS库存信息
     * @param storeCodes 仓库代码列表
     * @param sku SKU编码
     * @return 库存信息对象
     */
    protected InventoryInfo extractInventoryInfo(Map<String, Object> wmsInventory, List<String> storeCodes, String sku) {
        String skuSn = (String) wmsInventory.getOrDefault("sku_sn", null);
        BigDecimal totalAvailable = (BigDecimal) wmsInventory.getOrDefault("total_available", BigDecimal.ZERO);

        validateInventoryFields(skuSn, totalAvailable, storeCodes, sku, wmsInventory);

        return new InventoryInfo(skuSn, totalAvailable);
    }

    /**
     * 初始化分配上下文
     * @param ruleId 规则ID
     * @param skuSn SKU序列号
     * @param totalAvailable 总可用库存
     * @return 分配上下文
     */
    protected BaseAllocationContext initializeAllocationContext(Long ruleId, String skuSn, BigDecimal totalAvailable) {
        boolean isLockAllocation = isLockAllocation(ruleId.toString());
        List<String> storeCodes = getStoreCodesByRuleId(ruleId);
        List<RuleStockChannelInfo> channelInfoList = getRuleStockChannelInfoList(ruleId);

        return new BaseAllocationContext(ruleId, skuSn, totalAvailable, isLockAllocation, storeCodes, channelInfoList);
    }

    /**
     * 执行单个渠道的库存分配
     * @param context 分配上下文
     * @param channelInfo 渠道信息
     * @param amount 分配数量
     * @return 分配是否成功
     */
    protected boolean allocateToChannel(BaseAllocationContext context, RuleStockChannelInfo channelInfo, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

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
     * @param context 分配上下文
     * @param totalAllocatedAmount 总分配数量
     */
    protected void handleWmsInventoryLocking(BaseAllocationContext context, BigDecimal totalAllocatedAmount) {
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

        // 同步更新oms_inventory库存
        updateOmsInventoryForLocking(context.getSkuSn(), totalAllocatedAmount,
                context.getStoreCodes(), context.getRuleId().toString());
    }

    /**
     * 更新OMS库存以反映锁定操作
     * @param skuSn SKU编号
     * @param lockQuantity 锁定数量
     * @param storeCodes 仓库代码列表
     * @param relationSn 关联单号
     */
    protected void updateOmsInventoryForLocking(String skuSn, BigDecimal lockQuantity,
                                              List<String> storeCodes, String relationSn) {
        try {
            // 构建OMS库存对象进行预留操作
            OmsInventory omsInventory = new OmsInventory();
            omsInventory.setSkuSn(skuSn);
            omsInventory.setAllocatedStock(lockQuantity.intValue());
            // 假设使用第一个仓库代码作为公司代码，实际应根据业务逻辑调整
            omsInventory.setCompanyCode(storeCodes.isEmpty() ? "DEFAULT" : storeCodes.get(0));

            // 预留库存（减少可用库存，增加已分配库存）
            int updateResult = omsInventoryMapper.reserveStock(omsInventory);

            if (updateResult <= 0) {
                log.error("OMS库存预留失败，SKU: {}, 锁定数量: {}", skuSn, lockQuantity);
                throw new RuntimeException("OMS库存预留失败，可能库存不足");
            }

            log.info("OMS库存预留成功，SKU: {}, 锁定数量: {}", skuSn, lockQuantity);

        } catch (Exception e) {
            log.error("OMS库存更新异常，SKU: {}, 锁定数量: {}", skuSn, lockQuantity, e);
            throw new RuntimeException("OMS库存更新异常: " + e.getMessage(), e);
        }
    }

    /**
     * 处理全部商品的库存分配
     * 采用分页方式处理，避免一次性加载过多数据导致内存溢出
     *
     * @param ruleId 规则ID
     * @param storeCodes 仓库代码列表
     * @param processor SKU处理器函数
     */
    protected void processAllSkus(Long ruleId, List<String> storeCodes,
                                java.util.function.BiConsumer<Long, String> processor) {
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
            skuList.forEach(sku -> processor.accept(ruleId, sku));

            processedCount += skuList.size();
            currentPage++;
        }
    }

    /**
     * 处理指定商品的库存分配
     * 根据规则ID获取指定的SKU列表进行处理
     *
     * @param ruleId 规则ID
     * @param processor SKU处理器函数
     */
    protected void processSelectedSkus(Long ruleId, java.util.function.BiConsumer<Long, String> processor) {
        List<String> skuList = getSkuList(ruleId);
        log.info("开始处理指定商品，SKU数量: {}", skuList.size());
        log.debug("指定商品SKU列表: {}", skuList);

        skuList.forEach(sku -> processor.accept(ruleId, sku));
        log.info("指定商品处理完成");
    }
}
