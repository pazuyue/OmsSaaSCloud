package com.oms.inventory.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.entity.WmsInventory;

import java.util.List;

/**
 * <p>
 * 仓库库存表维度：sku_sn + store_code 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
public interface IWmsInventoryService extends IService<WmsInventory> {
    public List<WmsInventory> selectWmsInventoryList(WmsInventory wmsInventory);
}
