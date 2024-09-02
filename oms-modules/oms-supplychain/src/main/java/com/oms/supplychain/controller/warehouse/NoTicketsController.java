package com.oms.supplychain.controller.warehouse;

import java.util.List;
import java.io.IOException;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.common.api.RemoteGoodsService;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.oms.supplychain.model.entity.warehouse.NoTicketExcel;
import com.oms.supplychain.model.entity.warehouse.NoTickets;
import com.oms.supplychain.service.warehouse.INoTicketsService;
import com.ruoyi.common.core.domain.R;
import lombok.SneakyThrows;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * 采购入库通知单Controller
 *
 * @author ruoyi
 * @date 2024-08-25
 */
@RestController
@RequestMapping("/noTickets")
public class NoTicketsController extends BaseController
{
    @Resource
    private INoTicketsService noTicketsService;
    @Resource
    private RemoteGoodsService remoteGoodsService;

    /**
     * 查询采购入库通知单列表
     */
    @RequiresPermissions("warehouse:noTickets:list")
    @GetMapping("/list")
    public TableDataInfo list(NoTickets noTickets)
    {
        startPage();
        List<NoTickets> list = noTicketsService.selectNoTicketsList(noTickets);
        return getDataTable(list);
    }

    /**
     * 导出采购入库通知单列表
     */
    @RequiresPermissions("warehouse:noTickets:export")
    @Log(title = "采购入库通知单", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoTickets noTickets)
    {
        List<NoTickets> list = noTicketsService.selectNoTicketsList(noTickets);
        ExcelUtil<NoTickets> util = new ExcelUtil<NoTickets>(NoTickets.class);
        util.exportExcel(response, list, "采购入库通知单数据");
    }

    /**
     * 获取采购入库通知单详细信息
     */
    @RequiresPermissions("warehouse:noTickets:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(noTicketsService.selectNoTicketsById(id));
    }

    /**
     * 新增采购入库通知单
     */
    @RequiresPermissions("warehouse:noTickets:add")
    @Log(title = "采购入库通知单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoTickets noTickets)
    {
        return toAjax(noTicketsService.insertNoTickets(noTickets));
    }

    /**
     * 修改采购入库通知单
     */
    @RequiresPermissions("warehouse:noTickets:edit")
    @Log(title = "采购入库通知单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoTickets noTickets)
    {
        return toAjax(noTicketsService.updateNoTickets(noTickets));
    }

    /**
     * 删除采购入库通知单
     */
    @RequiresPermissions("warehouse:noTickets:remove")
    @Log(title = "采购入库通知单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(noTicketsService.deleteNoTicketsByIds(ids));
    }

    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) throws IOException
    {
        ExcelUtil<NoTicketExcel> util = new ExcelUtil<>(NoTicketExcel.class);
        util.importTemplateExcel(response, "商品导入模板数据");
    }


    /**
     * 商品导入
     * @param file
     * @return
     */
    @SneakyThrows
    @PostMapping(value = "/import")
    public AjaxResult export(MultipartFile file, String import_batch, String company_code) {
        System.out.println("export:"+company_code);
        try{
            ExcelUtil<NoTicketExcel> util = new ExcelUtil<>(NoTicketExcel.class);
            List<NoTicketExcel> noTicketGoodsList = util.importExcel(file.getInputStream());
            logger.debug("noTicketGoodsList:"+noTicketGoodsList.toString());
            R<GoodsSkuSnInfo> list = remoteGoodsService.selectGoodsSkuSnInfo(new GoodsSkuSnInfo(), company_code);
            logger.debug("getCode:"+list.getCode());
            logger.debug("getMsg:"+list.getMsg());
            logger.debug("getData:"+list.getData().toString());
            return success("导入失败");
        }catch (Exception e){
            return error(e.getMessage());
        }
    }
}

