package com.oms.supplychain.controller.warehouse;

import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import com.oms.supplychain.model.vo.warehouse.WmsRealStoreInfoVO;
import com.oms.supplychain.service.warehouse.WmsRealStoreInfoService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.List;


@Slf4j
@RestController
@RequestMapping("/realStore")
public class WmsRealStoreInfoController extends BaseController {
    @Resource
    private WmsRealStoreInfoService wmsRealStoreInfoService;

    /**
     * 新增实仓
     */
    @SneakyThrows
    @RequiresPermissions("warehouse:WmsRealStoreInfo:add")
    @Log(title = "实仓", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult save(@Validated @RequestBody WmsRealStoreInfoVO wmsRealStoreInfoVO, @RequestParam(value = "company_code") String companyCode){
        log.debug("wmsRealStoreInfoVO:"+wmsRealStoreInfoVO.toString());
        if (wmsRealStoreInfoService.save(wmsRealStoreInfoVO,companyCode))
            return success();
        return error("保存失败");
    }

    /**
     * 查询实仓列表
     */
    @RequiresPermissions("warehouse:WmsRealStoreInfo:list")
    @GetMapping("/list")
    @SneakyThrows
    public TableDataInfo list(@RequestBody WmsRealStoreInfoVO wmsRealStoreInfo)
    {
        startPage();
        List<WmsRealStoreInfo> list = wmsRealStoreInfoService.selectWmsRealStoreInfoList(wmsRealStoreInfo);
        return getDataTable(list);
    }

    @GetMapping("/listAll")
    @SneakyThrows
    public AjaxResult listAll(WmsRealStoreInfoVO wmsRealStoreInfo){
        List<WmsRealStoreInfo> list = wmsRealStoreInfoService.selectWmsRealStoreInfoList(wmsRealStoreInfo);
        return success(list);
    }

    /**
     * 导出实仓列表
     */
    @RequiresPermissions("warehouse:WmsRealStoreInfo:export")
    @Log(title = "实仓", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, WmsRealStoreInfoVO wmsRealStoreInfo)
    {
        List<WmsRealStoreInfo> list = wmsRealStoreInfoService.selectWmsRealStoreInfoList(wmsRealStoreInfo);
        ExcelUtil<WmsRealStoreInfo> util = new ExcelUtil<WmsRealStoreInfo>(WmsRealStoreInfo.class);
        util.exportExcel(response, list, "实仓数据");
    }

    /**
     * 获取实仓详细信息
     */
    @RequiresPermissions("warehouse:WmsRealStoreInfo:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(wmsRealStoreInfoService.selectWmsRealStoreInfoById(id));
    }

    /**
     * 修改实仓
     */
    @RequiresPermissions("warehouse:WmsRealStoreInfo:edit")
    @Log(title = "实仓", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody WmsRealStoreInfoVO wmsRealStoreInfo)
    {
        return toAjax(wmsRealStoreInfoService.updateWmsRealStoreInfo(wmsRealStoreInfo));
    }

    /**
     * 删除实仓
     */
    @RequiresPermissions("warehouse:WmsRealStoreInfo:remove")
    @Log(title = "实仓", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(wmsRealStoreInfoService.deleteWmsRealStoreInfoByIds(ids));
    }
}
