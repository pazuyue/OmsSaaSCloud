package com.oms.supplychain.controller.warehouse;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.supplychain.model.entity.warehouse.SupplierInfo;
import com.oms.supplychain.service.warehouse.SupplierInfoService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;

import com.ruoyi.common.core.web.page.TableDataInfo;

import java.util.List;

/**
 * 供应商主Controller
 *
 * @author ruoyi
 * @date 2024-08-20
 */
@RestController
@RequestMapping("/supplier")
public class SupplierInfoController extends BaseController
{
    @Resource
    private SupplierInfoService supplierInfoService;

    /**
     * 查询供应商主列表
     */
    @RequiresPermissions("warehouse:supplier:list")
    @GetMapping("/list")
    public TableDataInfo list(SupplierInfo supplierInfo)
    {
        startPage();
        List<SupplierInfo> list = supplierInfoService.selectSupplierInfoList(supplierInfo);
        return getDataTable(list);
    }

    /**
     * 导出供应商主列表
     */
    @RequiresPermissions("warehouse:supplier:export")
    @Log(title = "供应商主", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SupplierInfo supplierInfo)
    {
        List<SupplierInfo> list = supplierInfoService.selectSupplierInfoList(supplierInfo);
        ExcelUtil<SupplierInfo> util = new ExcelUtil<SupplierInfo>(SupplierInfo.class);
        util.exportExcel(response, list, "供应商主数据");
    }

    /**
     * 获取供应商主详细信息
     */
    @RequiresPermissions("warehouse:supplier:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(supplierInfoService.selectSupplierInfoById(id));
    }

    /**
     * 新增供应商主
     */
    @RequiresPermissions("warehouse:supplier:add")
    @Log(title = "供应商主", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SupplierInfo supplierInfo, @RequestParam(value = "company_code") String companyCode)
    {
        return toAjax(supplierInfoService.insertSupplierInfo(supplierInfo,companyCode));
    }

    /**
     * 修改供应商主
     */
    @RequiresPermissions("warehouse:supplier:edit")
    @Log(title = "供应商主", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SupplierInfo supplierInfo)
    {
        return toAjax(supplierInfoService.updateSupplierInfo(supplierInfo));
    }

    /**
     * 删除供应商主
     */
    @RequiresPermissions("warehouse:supplier:remove")
    @Log(title = "供应商主", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(supplierInfoService.deleteSupplierInfoByIds(ids));
    }
}

