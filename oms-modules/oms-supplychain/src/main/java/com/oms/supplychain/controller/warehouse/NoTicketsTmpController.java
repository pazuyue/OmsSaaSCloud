package com.oms.supplychain.controller.warehouse;

import com.oms.supplychain.model.entity.warehouse.NoTicketsGoodsTmp;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsTmpService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 入库通知单明细-未送审Controller
 *
 * @author ruoyi
 * @date 2024-09-05
 */
@RestController
@RequestMapping("/tmp")
public class NoTicketsTmpController extends BaseController
{
    @Resource
    private INoTicketsGoodsTmpService noTicketsGoodsTmpService;

    /**
     * 查询入库通知单明细-未送审列表
     */
    @RequiresPermissions("warehouse:noTicketsTmp:list")
    @GetMapping("/list")
    public TableDataInfo list(NoTicketsGoodsTmp noTicketsGoodsTmp)
    {
        startPage();
        List<NoTicketsGoodsTmp> list = noTicketsGoodsTmpService.selectNoTicketsGoodsTmpList(noTicketsGoodsTmp);
        return getDataTable(list);
    }

    /**
     * 导出入库通知单明细-未送审列表
     */
    @RequiresPermissions("warehouse:noTicketsTmp:export")
    @Log(title = "入库通知单明细-未送审", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoTicketsGoodsTmp noTicketsGoodsTmp)
    {
        List<NoTicketsGoodsTmp> list = noTicketsGoodsTmpService.selectNoTicketsGoodsTmpList(noTicketsGoodsTmp);
        ExcelUtil<NoTicketsGoodsTmp> util = new ExcelUtil<NoTicketsGoodsTmp>(NoTicketsGoodsTmp.class);
        util.exportExcel(response, list, "入库通知单明细-未送审数据");
    }

    /**
     * 获取入库通知单明细-未送审详细信息
     */
    @RequiresPermissions("warehouse:noTicketsTmp:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noTicketsGoodsTmpService.selectNoTicketsGoodsTmpById(id));
    }

    /**
     * 新增入库通知单明细-未送审
     */
    @RequiresPermissions("warehouse:noTicketsTmp:add")
    @Log(title = "入库通知单明细-未送审", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoTicketsGoodsTmp noTicketsGoodsTmp)
    {
        return toAjax(noTicketsGoodsTmpService.insertNoTicketsGoodsTmp(noTicketsGoodsTmp));
    }

    /**
     * 修改入库通知单明细-未送审
     */
    @RequiresPermissions("warehouse:noTicketsTmp:edit")
    @Log(title = "入库通知单明细-未送审", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoTicketsGoodsTmp noTicketsGoodsTmp)
    {
        return toAjax(noTicketsGoodsTmpService.updateNoTicketsGoodsTmp(noTicketsGoodsTmp));
    }

    /**
     * 删除入库通知单明细-未送审
     */
    @RequiresPermissions("warehouse:noTicketsTmp:remove")
    @Log(title = "入库通知单明细-未送审", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(noTicketsGoodsTmpService.deleteNoTicketsGoodsTmpByIds(ids));
    }
}
