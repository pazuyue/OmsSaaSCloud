package com.oms.inventory.controller.wms;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.service.IWmsInventoryService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 仓库库存表维度：sku_sn + store_code 前端控制器
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@RestController
@RequestMapping("/WmsInventory")
public class WmsInventoryController extends BaseController {


    @Resource
    private IWmsInventoryService wmsInventoryService;


    @PostMapping(value = "/list")
    public TableDataInfo list(@RequestBody WmsInventory wmsInventory) {
        startPage();
        List<WmsInventory> list =wmsInventoryService.selectWmsInventoryList(wmsInventory);
        return getDataTable(list);
    }
}
