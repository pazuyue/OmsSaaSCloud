package com.oms.inventory.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.TSkuInventoryMapper;
import com.oms.inventory.model.entity.TSkuInventory;
import com.oms.inventory.service.ITSkuInventoryService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 商品库存表维度：sku_sn + company_code 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@Service
public class TSkuInventoryServiceImpl extends ServiceImpl<TSkuInventoryMapper, TSkuInventory> implements ITSkuInventoryService {

}
