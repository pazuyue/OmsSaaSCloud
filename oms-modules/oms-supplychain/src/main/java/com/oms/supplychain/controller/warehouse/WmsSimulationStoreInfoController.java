package com.oms.supplychain.controller.warehouse;

import java.util.List;
import java.io.IOException;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.supplychain.model.entity.warehouse.WmsSimulationStoreInfo;
import com.oms.supplychain.service.warehouse.WmsSimulationStoreInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 虚仓Controller
 *
 * @author ruoyi
 * @date 2024-08-19
 */
@RestController
@RequestMapping("/simulationStore")
public class WmsSimulationStoreInfoController extends BaseController
{
    @Resource
    private WmsSimulationStoreInfoService wmsSimulationStoreInfoService;

    /**
     * 查询虚仓列表
     */
    @RequiresPermissions("warehouse:simulationStoreInfo:list")
    @GetMapping("/list")
    public TableDataInfo list(WmsSimulationStoreInfo wmsSimulationStoreInfo)
    {
        startPage();
        List<WmsSimulationStoreInfo> list = wmsSimulationStoreInfoService.selectWmsSimulationStoreInfoList(wmsSimulationStoreInfo);
        return getDataTable(list);
    }

    @RequiresPermissions("warehouse:simulationStoreInfo:listSimulationStore")
    @GetMapping("/listSimulationStore")
    public AjaxResult listSimulationStore(@RequestParam(value = "company_code",required = false) String companyCode)
    {
        return success(wmsSimulationStoreInfoService.listSimulationStore(companyCode));
    }

    /**
     * 导出虚仓列表
     */
    @RequiresPermissions("warehouse:simulationStoreInfo:export")
    @Log(title = "虚仓", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, WmsSimulationStoreInfo wmsSimulationStoreInfo)
    {
        List<WmsSimulationStoreInfo> list = wmsSimulationStoreInfoService.selectWmsSimulationStoreInfoList(wmsSimulationStoreInfo);
        ExcelUtil<WmsSimulationStoreInfo> util = new ExcelUtil<WmsSimulationStoreInfo>(WmsSimulationStoreInfo.class);
        util.exportExcel(response, list, "虚仓数据");
    }

    /**
     * 获取虚仓详细信息
     */
    @RequiresPermissions("warehouse:simulationStoreInfo:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(wmsSimulationStoreInfoService.selectWmsSimulationStoreInfoById(id));
    }

    /**
     * 新增虚仓
     */
    @RequiresPermissions("warehouse:simulationStoreInfo:add")
    @Log(title = "虚仓", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody WmsSimulationStoreInfo wmsSimulationStoreInfo, @RequestParam(value = "company_code") String companyCode)
    {
        return toAjax(wmsSimulationStoreInfoService.insertWmsSimulationStoreInfo(wmsSimulationStoreInfo,companyCode));
    }

    /**
     * 修改虚仓
     */
    @RequiresPermissions("warehouse:simulationStoreInfo:edit")
    @Log(title = "虚仓", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody WmsSimulationStoreInfo wmsSimulationStoreInfo)
    {
        return toAjax(wmsSimulationStoreInfoService.updateWmsSimulationStoreInfo(wmsSimulationStoreInfo));
    }

    /**
     * 删除虚仓
     */
    @RequiresPermissions("warehouse:simulationStoreInfo:remove")
    @Log(title = "虚仓", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(wmsSimulationStoreInfoService.deleteWmsSimulationStoreInfoByIds(ids));
    }
}

