package com.oms.inventory.controller.wms.api;

import cn.hutool.core.bean.BeanUtil;
import com.oms.inventory.model.entity.TSkuInventory;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.WmsInventoryBatchServiceImpl;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("/api/WmsInventory")
public class ApiWmsInventoryController extends BaseController {

    @Resource
    private WmsInventoryBatchServiceImpl wmsInventoryBatchService;
    /**
     * 入库 - 库存增加
     *
     * @param wmsInventoryBatch
     * @return
     */
    @PostMapping(value = "/addInventory")
    @SneakyThrows
    public AjaxResult addInventory(@RequestBody WmsInventoryBatch wmsInventoryBatch) {
        logger.debug("wmsInventoryBatch {}",wmsInventoryBatch);
        WmsInventory wmsInventory = new WmsInventory();
        TSkuInventory skuInventory = new TSkuInventory();
        try {
            BeanUtil.copyProperties(wmsInventoryBatch, wmsInventory);
            logger.debug("wmsInventory {}",wmsInventory);
            BeanUtil.copyProperties(wmsInventoryBatch, skuInventory);
            logger.debug("skuInventory {}",skuInventory);
            wmsInventoryBatchService.addInventory(skuInventory,wmsInventory, wmsInventoryBatch);
            return success();
        } catch (Throwable e) {
            return error(e.getMessage());
        }
    }
}
