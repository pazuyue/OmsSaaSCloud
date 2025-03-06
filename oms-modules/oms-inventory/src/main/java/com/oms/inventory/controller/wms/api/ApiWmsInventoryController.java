package com.oms.inventory.controller.wms.api;

import cn.hutool.core.bean.BeanUtil;
import com.oms.inventory.model.dto.WmsInventoryBatchDto;
import com.oms.inventory.model.entity.OmsInventory;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.impl.WmsInventoryBatchServiceImpl;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
     * @param dto
     * @return
     */
    @PostMapping(value = "/addInventory")
    @SneakyThrows
    public AjaxResult addInventory(@RequestBody WmsInventoryBatchDto dto, @RequestParam("company_code") String company_code) {
        logger.debug("WmsInventoryBatchDto {}",dto);

        try {
            WmsInventoryBatch wmsInventoryBatch = dto.getWmsInventoryBatch();
            wmsInventoryBatch.setCompanyCode(company_code);
            String relationSn = dto.getRelationSn();
            wmsInventoryBatchService.addInventory(wmsInventoryBatch,relationSn);
            return success();
        } catch (Throwable e) {
            return error(e.getMessage());
        }
    }
}
