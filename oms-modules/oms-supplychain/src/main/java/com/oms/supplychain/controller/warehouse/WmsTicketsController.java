package com.oms.supplychain.controller.warehouse;

import java.util.List;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import com.oms.supplychain.model.entity.warehouse.WmsTickets;
import com.oms.supplychain.service.warehouse.IWmsTicketsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 出入库单Controller
 *
 * @author ruoyi
 * @date 2024-10-14
 */
@RestController
@RequestMapping("/tickets")
public class WmsTicketsController extends BaseController
{
    @Resource
    private IWmsTicketsService wmsTicketsService;

    /**
     * 查询出入库单列表
     */
    @RequiresPermissions("system:tickets:list")
    @GetMapping("/list")
    public TableDataInfo list(WmsTickets wmsTickets)
    {
        startPage();
        List<WmsTickets> list = wmsTicketsService.selectWmsTicketsList(wmsTickets);
        return getDataTable(list);
    }

    /**
     * 导出出入库单列表
     */
    @RequiresPermissions("system:tickets:export")
    @Log(title = "出入库单", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, WmsTickets wmsTickets)
    {
        List<WmsTickets> list = wmsTicketsService.selectWmsTicketsList(wmsTickets);
        ExcelUtil<WmsTickets> util = new ExcelUtil<WmsTickets>(WmsTickets.class);
        util.exportExcel(response, list, "出入库单数据");
    }

    /**
     * 获取出入库单详细信息
     */
    @RequiresPermissions("system:tickets:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(wmsTicketsService.selectWmsTicketsById(id));
    }

    /**
     * 新增出入库单
     */
    @RequiresPermissions("system:tickets:add")
    @Log(title = "出入库单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody WmsTickets wmsTickets)
    {
        return toAjax(wmsTicketsService.insertWmsTickets(wmsTickets));
    }

    /**
     * 修改出入库单
     */
    @RequiresPermissions("system:tickets:edit")
    @Log(title = "出入库单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody WmsTickets wmsTickets)
    {
        return toAjax(wmsTicketsService.updateWmsTickets(wmsTickets));
    }

    /**
     * 删除出入库单
     */
    @RequiresPermissions("system:tickets:remove")
    @Log(title = "出入库单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(wmsTicketsService.deleteWmsTicketsByIds(ids));
    }
}

