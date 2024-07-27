package com.ruoyi.system.controller;

import java.util.List;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.server.PathParam;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.domain.SysCompanyModelAssociationConfig;
import com.ruoyi.system.service.ISysCompanyModelAssociationConfigService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 插件管理Controller
 *
 * @author ruoyi
 * @date 2024-07-26
 */
@RestController
@RequestMapping("/companyModelAssociationConfig")
public class SysCompanyModelAssociationConfigController extends BaseController
{
    @Autowired
    private ISysCompanyModelAssociationConfigService sysCompanyModelAssociationConfigService;

    /**
     * 查询插件管理列表
     */
    @RequiresPermissions("system:companyModelAssociationConfig:list")
    @GetMapping("/list")
    public TableDataInfo list(SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig)
    {
        startPage();
        List<SysCompanyModelAssociationConfig> list = sysCompanyModelAssociationConfigService.selectSysCompanyModelAssociationConfigList(sysCompanyModelAssociationConfig);
        return getDataTable(list);
    }

    /**
     * 导出插件管理列表
     */
    @RequiresPermissions("system:companyModelAssociationConfig:export")
    @Log(title = "插件管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig)
    {
        List<SysCompanyModelAssociationConfig> list = sysCompanyModelAssociationConfigService.selectSysCompanyModelAssociationConfigList(sysCompanyModelAssociationConfig);
        ExcelUtil<SysCompanyModelAssociationConfig> util = new ExcelUtil<SysCompanyModelAssociationConfig>(SysCompanyModelAssociationConfig.class);
        util.exportExcel(response, list, "插件管理数据");
    }

    /**
     * 获取插件管理详细信息
     */
    @RequiresPermissions("system:companyModelAssociationConfig:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(sysCompanyModelAssociationConfigService.selectSysCompanyModelAssociationConfigById(id));
    }

    /**
     * 根据companyCode获取插件管理详细信息
     */
    @PostMapping(value = "/getInfo")
    public AjaxResult getInfoByCompanyCode(@RequestParam("company_code") String companyCode)
    {
        return success(sysCompanyModelAssociationConfigService.selectSysCompanyModelAssociationConfigByCompanyCode(companyCode));
    }

    /**
     * 新增插件管理
     */
    @RequiresPermissions("system:companyModelAssociationConfig:add")
    @Log(title = "插件管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig)
    {
        return toAjax(sysCompanyModelAssociationConfigService.insertSysCompanyModelAssociationConfig(sysCompanyModelAssociationConfig));
    }

    /**
     * 修改插件管理
     */
    @RequiresPermissions("system:companyModelAssociationConfig:edit")
    @Log(title = "插件管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig)
    {
        return toAjax(sysCompanyModelAssociationConfigService.updateSysCompanyModelAssociationConfig(sysCompanyModelAssociationConfig));
    }

    /**
     * 删除插件管理
     */
    @RequiresPermissions("system:companyModelAssociationConfig:remove")
    @Log(title = "插件管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(sysCompanyModelAssociationConfigService.deleteSysCompanyModelAssociationConfigByIds(ids));
    }
}

