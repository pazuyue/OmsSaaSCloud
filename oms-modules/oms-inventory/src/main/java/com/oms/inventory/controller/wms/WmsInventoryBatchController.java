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
import java.util.Arrays;
import com.oms.inventory.service.InventoryCompany;
import com.github.pagehelper.PageHelper;
import com.oms.inventory.service.impl.BatchInventoryQueryService;

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
    @Resource private BatchInventoryQueryService queries;

    /**
     * 查询仓库批次库存列表
     */
    @RequiresPermissions("wmsInventoryBatch:batch:list")
    @GetMapping("/list")
    public TableDataInfo list(WmsInventoryBatch wmsInventoryBatch,
            @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) {
        wmsInventoryBatch.setCompanyCode(InventoryCompany.current());
        PageHelper.startPage(Math.max(1, Math.min(pageNum,100000)), Math.max(1,Math.min(pageSize,100)));
        List<WmsInventoryBatch> list = wmsInventoryBatchService.list(
            wmsInventoryBatchService.lambdaQuery()
                .eq(wmsInventoryBatch.getStoreCode() != null, WmsInventoryBatch::getStoreCode, wmsInventoryBatch.getStoreCode())
                .eq(wmsInventoryBatch.getSkuSn() != null, WmsInventoryBatch::getSkuSn, wmsInventoryBatch.getSkuSn())
                .eq(wmsInventoryBatch.getCompanyCode() != null, WmsInventoryBatch::getCompanyCode, wmsInventoryBatch.getCompanyCode())
                .like(wmsInventoryBatch.getBatchCode() != null, WmsInventoryBatch::getBatchCode, wmsInventoryBatch.getBatchCode())
                .like(wmsInventoryBatch.getBrandCode() != null, WmsInventoryBatch::getBrandCode, wmsInventoryBatch.getBrandCode())
                .orderByDesc(WmsInventoryBatch::getId)
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
        wmsInventoryBatch.setCompanyCode(InventoryCompany.current());
        List<WmsInventoryBatch> list = wmsInventoryBatchService.list(
            wmsInventoryBatchService.lambdaQuery()
                .eq(wmsInventoryBatch.getStoreCode() != null, WmsInventoryBatch::getStoreCode, wmsInventoryBatch.getStoreCode())
                .eq(wmsInventoryBatch.getSkuSn() != null, WmsInventoryBatch::getSkuSn, wmsInventoryBatch.getSkuSn())
                .eq(wmsInventoryBatch.getCompanyCode() != null, WmsInventoryBatch::getCompanyCode, wmsInventoryBatch.getCompanyCode())
                .like(wmsInventoryBatch.getBatchCode() != null, WmsInventoryBatch::getBatchCode, wmsInventoryBatch.getBatchCode())
                .like(wmsInventoryBatch.getBrandCode() != null, WmsInventoryBatch::getBrandCode, wmsInventoryBatch.getBrandCode())
                .orderByDesc(WmsInventoryBatch::getId)
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
        return success(queries.detail(InventoryCompany.current(),id));
    }

    @RequiresPermissions("wmsInventoryBatch:batch:query")
    @GetMapping("/{id}/history")
    public TableDataInfo history(@PathVariable long id,@RequestParam(defaultValue="") String operation,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return queries.history(InventoryCompany.current(),id,operation,pageNum,pageSize);
    }
    @RequiresPermissions("wmsInventoryBatch:batch:query")
    @GetMapping("/{id}/sources")
    public TableDataInfo sources(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return queries.sources(InventoryCompany.current(),id,pageNum,pageSize);
    }
    @RequiresPermissions("wmsInventoryBatch:batch:query")
    @GetMapping("/{id}/sources/{sourceId}/orders")
    public TableDataInfo orders(@PathVariable long id,@PathVariable long sourceId,@RequestParam(defaultValue="true") boolean onlyOccupied,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return queries.orders(InventoryCompany.current(),id,sourceId,onlyOccupied,pageNum,pageSize);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}

    /**
     * 新增仓库批次库存
     */
    @RequiresPermissions("wmsInventoryBatch:batch:add")
    @Log(title = "仓库批次库存", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody WmsInventoryBatch wmsInventoryBatch) {
        return error("请通过入库或批次库存调整操作，禁止直接修改批次账面数据");
    }

    /**
     * 修改仓库批次库存
     */
    @RequiresPermissions("wmsInventoryBatch:batch:edit")
    @Log(title = "仓库批次库存", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody WmsInventoryBatch wmsInventoryBatch) {
        return error("请通过入库或批次库存调整操作，禁止直接修改批次账面数据");
    }

    /**
     * 删除仓库批次库存
     */
    @RequiresPermissions("wmsInventoryBatch:batch:remove")
    @Log(title = "仓库批次库存", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return error("请通过入库或批次库存调整操作，禁止直接修改批次账面数据");
    }
}
