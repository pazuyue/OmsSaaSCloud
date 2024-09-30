package com.oms.supplychain.controller.warehouse;

import com.oms.supplychain.model.entity.warehouse.NoTicketsGoods;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 采购入库通知单明细
 *
 * @author ruoyi
 * @date 2024-08-25
 */
@RestController
@RequestMapping("/noTicketsGoods")
public class NoTicketsGoodsController extends BaseController {
    @Resource
    private INoTicketsGoodsService noTicketsGoodsService;

    /**
     * 查询入库通知单明细列表
     */
    @RequiresPermissions("warehouse:noTickets:list")
    @GetMapping("/list")
    public TableDataInfo list(NoTicketsGoods noTicketsGoods)
    {
        startPage();
        List<NoTicketsGoods> list = noTicketsGoodsService.selectNoTicketsGoodsList(noTicketsGoods);
        return getDataTable(list);
    }
}
