package com.oms.inventory.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.OmsChannelInventoryMapper;
import com.oms.inventory.mapper.RelationSn;
import com.oms.inventory.model.entity.OmsChannelInventory;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.service.IOmsChannelInventoryService;
import com.oms.inventory.service.rule.IRuleStockInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
@Slf4j
@Service
public class OmsChannelInventoryServiceImpl extends ServiceImpl<OmsChannelInventoryMapper, OmsChannelInventory> implements IOmsChannelInventoryService {

    @Resource
    private IRuleStockInfoService ruleStockInfoService;

    /**
     * 判断是否为锁库单分货
     * @param relationSn 关联单号（规则ID）
     * @return true-锁库单分货，false-非锁库单分货
     */
    private boolean isLockAllocation(String relationSn) {
        try {
            // 尝试将relationSn转换为Long类型的规则ID
            Long ruleId = Long.parseLong(relationSn);
            RuleStockInfo ruleStockInfo = ruleStockInfoService.selectRuleStockInfoById(ruleId);

            // 判断规则类型是否为3（锁库时分货）
            return ruleStockInfo != null && ruleStockInfo.getRuleType() != null && ruleStockInfo.getRuleType() == 3;
        } catch (NumberFormatException e) {
            // 如果relationSn不是数字，则不是规则ID，返回false
            log.debug("relationSn is not a valid rule ID: {}", relationSn);
            return false;
        } catch (Exception e) {
            log.error("Error checking lock allocation for relationSn: {}", relationSn, e);
            return false;
        }
    }
    @Override
    public Boolean allocationInventory(@RelationSn("relationSn") String relationSn,Integer channelId, String SkuSn, String companyCode, BigDecimal availableStock) {
        // 判断是否为锁库单分货（通过relationSn判断，如果relationSn是数字且对应的规则类型为3，则为锁库单）
        boolean isLockAllocation = isLockAllocation(relationSn);

        QueryWrapper wrapper = new QueryWrapper();
        wrapper.eq("channel_id", channelId);
        wrapper.eq("sku_sn", SkuSn);
        OmsChannelInventory channelInventory = this.baseMapper.selectOne(wrapper);

        if (ObjectUtil.isEmpty(channelInventory)){
            channelInventory = new OmsChannelInventory();
            channelInventory.setChannelId(channelId);
            channelInventory.setSkuSn(SkuSn);
            channelInventory.setCompanyCode(companyCode);

            if (isLockAllocation) {
                // 锁库单：更新allocated_stock字段
                channelInventory.setAllocatedStock(availableStock);
                channelInventory.setAvailableStock(BigDecimal.ZERO); // 初始化为0
            } else {
                // 非锁库单：更新available_stock字段
                channelInventory.setAvailableStock(availableStock);
                channelInventory.setAllocatedStock(BigDecimal.ZERO); // 初始化为0
            }

            log.debug("channelInventory is null,insert channelInventory:{}", channelInventory);
            return this.save(channelInventory);
        } else {
            if (isLockAllocation) {
                // 锁库单：更新allocated_stock字段
                BigDecimal currentAllocatedStock = channelInventory.getAllocatedStock() != null ? channelInventory.getAllocatedStock() : BigDecimal.ZERO;
                BigDecimal newAllocatedStock = currentAllocatedStock.add(availableStock);

                if (newAllocatedStock.compareTo(currentAllocatedStock) == 0) {
                    log.debug("allocatedStock is equal to current allocatedStock,no need to update channelInventory:{}", channelInventory);
                    return false;
                }

                channelInventory.setAllocatedStock(newAllocatedStock);
                log.debug("update channelInventory allocated_stock:{}", channelInventory);
            } else {
                // 非锁库单：更新available_stock字段
                //availableStock = availableStock - reserved_stock - frozen_stock
                BigDecimal reservedStock = channelInventory.getReservedStock();
                BigDecimal frozenStock = channelInventory.getFrozenStock();
                availableStock = availableStock.subtract(reservedStock).subtract(frozenStock);

                //如果availableStock == channelInventory.getAvailableStock() 则不更新channelInventory
                if (availableStock.compareTo(channelInventory.getAvailableStock()) == 0) {
                    log.debug("availableStock is equal to channelInventory.getAvailableStock(),no need to update channelInventory:{}", channelInventory);
                    return false;
                }

                channelInventory.setAvailableStock(availableStock);
                log.debug("update channelInventory available_stock:{}", channelInventory);
            }

            return this.updateById(channelInventory);
        }
    }
}
