package com.oms.inventory.service.impl.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import com.oms.inventory.annotation.StrategyType;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.rule.RuleStockChannelInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.entity.rule.RuleStockStoreCodeInfo;
import com.oms.inventory.service.IOmsChannelInventoryService;
import com.oms.inventory.service.IWmsInventoryService;
import com.oms.inventory.service.rule.IRuleStockChannelInfoService;
import com.oms.inventory.service.rule.IRuleStockInfoService;
import com.oms.inventory.service.rule.IRuleStockStoreCodeInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        return new ArrayList<>();
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
}
