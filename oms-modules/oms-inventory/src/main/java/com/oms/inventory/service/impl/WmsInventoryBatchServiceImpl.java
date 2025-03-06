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

    @Transactional
    public Boolean addInventory(WmsInventoryBatch wmsInventoryBatch, String relationSn)
    {
        try {
            WmsInventory wmsInventory = new WmsInventory();
            BeanUtil.copyProperties(wmsInventoryBatch, wmsInventory);
            OmsInventory omsInventory = this.getOmsInventory(wmsInventory);
            log.debug("wmsInventory {}",wmsInventory);
            wmsInventoryService.getBaseMapper().insertOrUpdate(wmsInventory);
            this.baseMapper.insertOrUpdate(wmsInventoryBatch);
            omsInventoryService.getBaseMapper().insertOrUpdate(relationSn,omsInventory);
            return true;
        } catch (OptimisticLockingFailureException e) {
            // 记录日志或采取其他措施
            log.error("Cause: {} Optimistic lock failed: {}",e.getCause(),  e.getMessage());
            return false;
        } catch (Exception e) {
            // 捕获其他可能的异常
            log.error("Error during inventory addition: {}",e.getMessage());
            throw new RuntimeException("Failed to add inventory", e);
        }
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
