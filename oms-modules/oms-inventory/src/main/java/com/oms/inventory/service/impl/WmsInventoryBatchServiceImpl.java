package com.oms.inventory.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.WmsInventoryBatchMapper;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.IWmsInventoryBatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * <p>
 * 仓库库存表-批次维度：sku_sn + company_code + batch_code + store_code 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@Service
public class WmsInventoryBatchServiceImpl extends ServiceImpl<WmsInventoryBatchMapper, WmsInventoryBatch> implements IWmsInventoryBatchService {

    @Resource
    private WmsInventoryServiceImpl wmsInventoryService;

    @Transactional
    public Boolean addInventory( WmsInventory wmsInventory, WmsInventoryBatch wmsInventoryBatch)
    {
        wmsInventoryService.getBaseMapper().insertOrUpdate(wmsInventory);
        this.baseMapper.insertOrUpdate(wmsInventoryBatch);
        return true;
    }
}
