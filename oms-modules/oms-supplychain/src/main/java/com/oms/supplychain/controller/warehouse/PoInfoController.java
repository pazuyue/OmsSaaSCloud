package com.oms.supplychain.controller.warehouse;

import java.util.List;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.supplychain.model.entity.warehouse.PoInfo;
import com.oms.supplychain.service.warehouse.PoInfoService;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 采购单Controller
 *
 * @author ruoyi
 * @date 2024-08-21
 */
@RestController
@RequestMapping("/poInfo")
public class PoInfoController extends BaseController
{
    @Resource
    private PoInfoService poInfoService;

    /**
     * 查询采购单列表
     */
    @RequiresPermissions("warehouse:poInfo:list")
    @GetMapping("/list")
    public TableDataInfo list(PoInfo poInfo)
    {
        startPage();
        List<PoInfo> list = poInfoService.selectPoInfoList(poInfo);
        return getDataTable(list);
    }

    /**
     * 导出采购单列表
     */
    @RequiresPermissions("warehouse:poInfo:export")
    @Log(title = "采购单", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, PoInfo poInfo)
    {
        List<PoInfo> list = poInfoService.selectPoInfoList(poInfo);
        ExcelUtil<PoInfo> util = new ExcelUtil<PoInfo>(PoInfo.class);
        util.exportExcel(response, list, "采购单数据");
    }

    /**
     * 获取采购单详细信息
     */
    @RequiresPermissions("warehouse:poInfo:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(poInfoService.selectPoInfoById(id));
    }

    /**
     * 新增采购单
     */
    @RequiresPermissions("warehouse:poInfo:add")
    @Log(title = "采购单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody PoInfo poInfo, @RequestParam(value = "company_code") String companyCode)
    {
        return toAjax(poInfoService.insertPoInfo(poInfo,companyCode));
    }

    /**
     * 修改采购单
     */
    @RequiresPermissions("warehouse:poInfo:edit")
    @Log(title = "采购单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody PoInfo poInfo)
    {
        return toAjax(poInfoService.updatePoInfo(poInfo));
    }

    /**
     * 删除采购单
     */
    @RequiresPermissions("warehouse:poInfo:remove")
    @Log(title = "采购单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(poInfoService.deletePoInfoByIds(ids));
    }
}
