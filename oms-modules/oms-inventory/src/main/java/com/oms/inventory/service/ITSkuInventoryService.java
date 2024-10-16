package com.oms.inventory.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.entity.TSkuInventory;

/**
 * <p>
 * 商品库存表维度：sku_sn + company_code 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
public interface ITSkuInventoryService extends IService<TSkuInventory> {

}
