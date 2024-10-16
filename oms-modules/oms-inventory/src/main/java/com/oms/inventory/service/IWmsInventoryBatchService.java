package com.oms.inventory.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.entity.WmsInventoryBatch;

/**
 * <p>
 * 仓库库存表-批次维度：sku_sn + company_code + batch_code + store_code 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
public interface IWmsInventoryBatchService extends IService<WmsInventoryBatch> {

}
