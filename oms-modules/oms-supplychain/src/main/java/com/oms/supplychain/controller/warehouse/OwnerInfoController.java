package com.oms.supplychain.controller.warehouse;


import cn.hutool.core.lang.Console;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.model.vo.warehouse.OwnerInfoVO;
import com.oms.supplychain.service.warehouse.OwnerInfoService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

@RestController
@RequestMapping("/owner")
public class OwnerInfoController extends BaseController {

    @Resource
    private OwnerInfoService ownerInfoService;

    @SneakyThrows
    @PostMapping(value = "/save")
    public AjaxResult save(@Validated OwnerInfoVO ownerInfoVO, @RequestParam(value = "company_code") String companyCode){
        Console.log(ownerInfoVO.toString());
        if (ownerInfoService.save(ownerInfoVO,companyCode))
            return success();
        return error("保存失败");
    }


    /**
     * 查询货主基础信息列表
     */
    @RequiresPermissions("warehouse:owner:list")
    @GetMapping("/list")
    public TableDataInfo list(OwnerInfo ownerInfo)
    {
        startPage();
        List<OwnerInfo> list = ownerInfoService.selectOwnerInfoList(ownerInfo);
        return getDataTable(list);
    }

    @RequiresPermissions("warehouse:owner:listOwner")
    @GetMapping("/listOwner")
    public AjaxResult listOwner(@RequestParam(value = "company_code",required = false) String companyCode){
        List<OwnerInfo> list = ownerInfoService.listOwner(companyCode);
        return success(list);
    }

    /**
     * 导出货主基础信息列表
     */
    @RequiresPermissions("warehouse:owner:export")
    @Log(title = "货主基础信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, OwnerInfo ownerInfo)
    {
        List<OwnerInfo> list = ownerInfoService.selectOwnerInfoList(ownerInfo);
        ExcelUtil<OwnerInfo> util = new ExcelUtil<OwnerInfo>(OwnerInfo.class);
        util.exportExcel(response, list, "货主基础信息数据");
    }

    /**
     * 获取货主基础信息详细信息
     */
    @RequiresPermissions("warehouse:owner:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(ownerInfoService.selectOwnerInfoById(id));
    }

    /**
     * 新增货主基础信息
     */
    @RequiresPermissions("warehouse:owner:add")
    @Log(title = "货主基础信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody OwnerInfo ownerInfo)
    {
        return toAjax(ownerInfoService.insertOwnerInfo(ownerInfo));
    }

    /**
     * 修改货主基础信息
     */
    @RequiresPermissions("warehouse:owner:edit")
    @Log(title = "货主基础信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody OwnerInfo ownerInfo)
    {
        return toAjax(ownerInfoService.updateOwnerInfo(ownerInfo));
    }

    /**
     * 删除货主基础信息
     */
    @RequiresPermissions("warehouse:owner:remove")
    @Log(title = "货主基础信息", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(ownerInfoService.deleteOwnerInfoByIds(ids));
    }

}

