package com.oms.inventory.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.WmsInventoryBatchMapper;
import com.oms.inventory.model.entity.OmsInventory;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.IOmsInventoryService;
import com.oms.inventory.service.IWmsInventoryBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.sql.SQLException;

/**
 * <p>
 * 仓库库存表-批次维度：sku_sn + company_code + batch_code + store_code 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@Slf4j
@Service
public class WmsInventoryBatchServiceImpl extends ServiceImpl<WmsInventoryBatchMapper, WmsInventoryBatch> implements IWmsInventoryBatchService {

    @Resource
    private WmsInventoryServiceImpl wmsInventoryService;
    @Resource
    private OmsInventoryServiceImpl omsInventoryService;

    @Resource
    private InventoryMutationService mutations;

    @Transactional
    public Boolean addInventory(WmsInventoryBatch batch, String relationSn) {
        return mutations.receive(batch, relationSn);
    }

    private OmsInventory getOmsInventory(WmsInventory wmsInventory)
    {
        OmsInventory omsInventory = new OmsInventory();
        int availableStock = wmsInventory.getZpAvailableNumber() + wmsInventory.getCpAvailableNumber();
        omsInventory.setSkuSn(wmsInventory.getSkuSn());
        omsInventory.setAvailableStock(availableStock);
        omsInventory.setTotalStock(availableStock);
        omsInventory.setCompanyCode(wmsInventory.getCompanyCode());
        return omsInventory;
    }
}
