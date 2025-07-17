package com.oms.inventory.controller.wms;

import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.oms.inventory.service.IWmsInventoryBatchService;
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
 * 仓库批次库存Controller
 *
 * @author AI Assistant
 * @date 2024-01-01
 */
@RestController
@RequestMapping("/wmsInventory/wmsInventoryBatch")
public class WmsInventoryBatchController extends BaseController {

    @Resource
    private IWmsInventoryBatchService wmsInventoryBatchService;

    /**
     * 查询仓库批次库存列表
     */
    @RequiresPermissions("wmsInventoryBatch:batch:list")
    @GetMapping("/list")
    public TableDataInfo list(WmsInventoryBatch wmsInventoryBatch) {
        startPage();
        List<WmsInventoryBatch> list = wmsInventoryBatchService.list(
            wmsInventoryBatchService.lambdaQuery()
                .like(wmsInventoryBatch.getStoreCode() != null, WmsInventoryBatch::getStoreCode, wmsInventoryBatch.getStoreCode())
                .like(wmsInventoryBatch.getSkuSn() != null, WmsInventoryBatch::getSkuSn, wmsInventoryBatch.getSkuSn())
                .like(wmsInventoryBatch.getBatchCode() != null, WmsInventoryBatch::getBatchCode, wmsInventoryBatch.getBatchCode())
                .like(wmsInventoryBatch.getBrandCode() != null, WmsInventoryBatch::getBrandCode, wmsInventoryBatch.getBrandCode())
                .orderByDesc(WmsInventoryBatch::getModifyTime)
                .getWrapper()
        );
        return getDataTable(list);
    }

    /**
     * 导出仓库批次库存列表
     */
    @RequiresPermissions("wmsInventoryBatch:batch:export")
    @Log(title = "仓库批次库存", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, WmsInventoryBatch wmsInventoryBatch) {
        List<WmsInventoryBatch> list = wmsInventoryBatchService.list(
            wmsInventoryBatchService.lambdaQuery()
                .like(wmsInventoryBatch.getStoreCode() != null, WmsInventoryBatch::getStoreCode, wmsInventoryBatch.getStoreCode())
                .like(wmsInventoryBatch.getSkuSn() != null, WmsInventoryBatch::getSkuSn, wmsInventoryBatch.getSkuSn())
                .like(wmsInventoryBatch.getBatchCode() != null, WmsInventoryBatch::getBatchCode, wmsInventoryBatch.getBatchCode())
                .like(wmsInventoryBatch.getBrandCode() != null, WmsInventoryBatch::getBrandCode, wmsInventoryBatch.getBrandCode())
                .orderByDesc(WmsInventoryBatch::getModifyTime)
                .getWrapper()
        );
        ExcelUtil<WmsInventoryBatch> util = new ExcelUtil<WmsInventoryBatch>(WmsInventoryBatch.class);
        util.exportExcel(response, list, "仓库批次库存数据");
    }

    /**
     * 获取仓库批次库存详细信息
     */
    @RequiresPermissions("wmsInventoryBatch:batch:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(wmsInventoryBatchService.getById(id));
    }

    /**
     * 新增仓库批次库存
     */
    @RequiresPermissions("wmsInventoryBatch:batch:add")
    @Log(title = "仓库批次库存", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody WmsInventoryBatch wmsInventoryBatch) {
        return toAjax(wmsInventoryBatchService.save(wmsInventoryBatch));
    }

    /**
     * 修改仓库批次库存
     */
    @RequiresPermissions("wmsInventoryBatch:batch:edit")
    @Log(title = "仓库批次库存", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody WmsInventoryBatch wmsInventoryBatch) {
        return toAjax(wmsInventoryBatchService.updateById(wmsInventoryBatch));
    }

    /**
     * 删除仓库批次库存
     */
    @RequiresPermissions("wmsInventoryBatch:batch:remove")
    @Log(title = "仓库批次库存", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(wmsInventoryBatchService.removeByIds(List.of(ids)));
    }
}
