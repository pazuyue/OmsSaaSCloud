package com.oms.inventory.controller.wms;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.service.IWmsInventoryService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
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
@RequestMapping("/wmsInventory")
public class WmsInventoryController extends BaseController {


    @Resource
    private IWmsInventoryService wmsInventoryService;


    /**
     * 查询仓库库存列表
     */
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping("/list")
    public TableDataInfo list(WmsInventory wmsInventory)
    {
        startPage();
        List<WmsInventory> list = wmsInventoryService.selectWmsInventoryList(wmsInventory);
        return getDataTable(list);
    }

    /**
     * 导出仓库库存列表
     */
    @RequiresPermissions("wmsInventory:inventory:export")
    @Log(title = "仓库库存", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, WmsInventory wmsInventory)
    {
        List<WmsInventory> list = wmsInventoryService.selectWmsInventoryList(wmsInventory);
        ExcelUtil<WmsInventory> util = new ExcelUtil<WmsInventory>(WmsInventory.class);
        util.exportExcel(response, list, "仓库库存数据");
    }

    /**
     * 获取仓库库存详细信息
     */
    @RequiresPermissions("wmsInventory:inventory:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(wmsInventoryService.selectWmsInventoryById(id));
    }

    /**
     * 修改仓库库存
     */
    @RequiresPermissions("wmsInventory:inventory:edit")
    @Log(title = "仓库库存", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody WmsInventory wmsInventory)
    {
        return toAjax(wmsInventoryService.updateWmsInventory(wmsInventory));
    }
}
