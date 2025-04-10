package com.oms.inventory.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.mapper.RelationSn;
import com.oms.inventory.model.entity.OmsChannelInventory;

import java.math.BigDecimal;

public interface IOmsChannelInventoryService extends IService<OmsChannelInventory> {

    Boolean allocationInventory(@RelationSn("relationSn") String relationSn,Integer channelId, String SkuSn, String companyCode, BigDecimal availableStock);
}
