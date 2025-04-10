package com.oms.inventory.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.OmsChannelInventoryMapper;
import com.oms.inventory.mapper.RelationSn;
import com.oms.inventory.model.entity.OmsChannelInventory;
import com.oms.inventory.service.IOmsChannelInventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
@Slf4j
@Service
public class OmsChannelInventoryServiceImpl extends ServiceImpl<OmsChannelInventoryMapper, OmsChannelInventory> implements IOmsChannelInventoryService {
    @Override
    public Boolean allocationInventory(@RelationSn("relationSn") String relationSn,Integer channelId, String SkuSn, String companyCode, BigDecimal availableStock) {
        QueryWrapper wrapper = new QueryWrapper();
        wrapper.eq("channel_id", channelId);
        wrapper.eq("sku_sn", SkuSn);
        OmsChannelInventory channelInventory = this.baseMapper.selectOne(wrapper);
        if (ObjectUtil.isEmpty(channelInventory)){
            channelInventory = new OmsChannelInventory();
            channelInventory.setChannelId(channelId);
            channelInventory.setSkuSn(SkuSn);
            channelInventory.setAvailableStock(availableStock);
            channelInventory.setCompanyCode(companyCode);
            log.debug("channelInventory is null,insert channelInventory:{}", channelInventory);
            return this.save(channelInventory);
        }else {
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
            return this.updateById(channelInventory);
        }
    }
}
