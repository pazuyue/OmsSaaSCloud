package com.oms.inventory.controller.rule;

import com.oms.inventory.model.entity.rule.RuleStockGoodsInfo;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.model.vo.RuleStockExcel;
import com.oms.inventory.service.rule.IRuleStockInfoService;
import com.oms.inventory.service.rule.RuleStockGoodsInfoService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import lombok.SneakyThrows;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 分货单基础信息Controller
 *
 * @author ruoyi
 * @date 2024-12-13
 */
@RestController
@RequestMapping("/ruleStock")
public class RuleStockInfoController extends BaseController
{
    @Resource
    private IRuleStockInfoService ruleStockInfoService;

    /**
     * 查询分货单基础信息列表
     */
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/list")
    public TableDataInfo list(RuleStockInfo ruleStockInfo)
    {
        startPage();
        List<RuleStockInfo> list = ruleStockInfoService.selectRuleStockInfoList(ruleStockInfo);
        return getDataTable(list);
    }

    /**
     * 导出分货单基础信息列表
     */
    @RequiresPermissions("ruleStock:info:export")
    @Log(title = "分货单基础信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, RuleStockInfo ruleStockInfo)
    {
        List<RuleStockInfo> list = ruleStockInfoService.selectRuleStockInfoList(ruleStockInfo);
        ExcelUtil<RuleStockInfo> util = new ExcelUtil<RuleStockInfo>(RuleStockInfo.class);
        util.exportExcel(response, list, "分货单基础信息数据");

    }

    /**
     * 获取分货单基础信息详细信息
     */
    @RequiresPermissions("ruleStock:info:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(ruleStockInfoService.selectRuleStockInfoById(id));
    }

    /**
     * 新增分货单基础信息
     */
    @RequiresPermissions("ruleStock:info:add")
    @Log(title = "分货单基础信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody RuleStockInfo ruleStockInfo,@RequestParam(value = "company_code",required = false) String companyCode)
    {
        ruleStockInfo.setCompanyCode(companyCode);
        return toAjax(ruleStockInfoService.insertRuleStockInfo(ruleStockInfo));
    }

    /**
     * 修改分货单基础信息
     */
    @RequiresPermissions("ruleStock:info:edit")
    @Log(title = "分货单基础信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody RuleStockInfo ruleStockInfo)
    {
        return toAjax(ruleStockInfoService.updateRuleStockInfo(ruleStockInfo));
    }

    /**
     * 删除分货单基础信息
     */
    @RequiresPermissions("ruleStock:info:remove")
    @Log(title = "分货单基础信息", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(ruleStockInfoService.deleteRuleStockInfoByIds(ids));
    }

    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) throws IOException
    {
        ExcelUtil<RuleStockExcel> util = new ExcelUtil<>(RuleStockExcel.class);
        util.importTemplateExcel(response, "分货单基础信息数据");
    }
}
