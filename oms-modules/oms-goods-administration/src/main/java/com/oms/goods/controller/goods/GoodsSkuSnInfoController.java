package com.oms.goods.controller.goods;

import java.util.List;
import java.io.IOException;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;
import com.oms.goods.service.goods.GoodsSkuSnInfoService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 产品信息Controller
 *
 * @author ruoyi
 * @date 2024-08-01
 */
@RestController
@RequestMapping("/info")
public class GoodsSkuSnInfoController extends BaseController
{
    @Resource
    private GoodsSkuSnInfoService goodsSkuSnInfoService;

    /** Bounded inventory lookup: one request for a page of SKUs, or a small search result. */
    @RequiresPermissions("wmsInventory:inventory:list")
    @GetMapping("/inventoryLookup")
    public AjaxResult inventoryLookup(@RequestParam(required=false) List<String> skus,
                                      @RequestParam(required=false) String keyword) {
        com.ruoyi.system.api.model.LoginUser user = com.ruoyi.common.security.utils.SecurityUtils.getLoginUser();
        String company = user.getCompanyCode();
        if (company == null || company.isEmpty()) company = user.getSysUser().getLoginCompanyCode();
        if (company == null || company.isEmpty()) return error("请先选择登录公司");
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<GoodsSkuSnInfo> query = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        query.select("sku_sn", "goods_name", "barcode_sn").eq("company_code",company);
        if (skus != null && !skus.isEmpty()) {
            if (skus.size()>100) return error("每次最多查询 100 个 SKU");
            query.in("sku_sn",skus);
        } else {
            if (keyword == null || keyword.trim().isEmpty()) return success(java.util.Collections.emptyList());
            String term=keyword.trim();
            if (term.length()>128) return error("搜索内容过长");
            query.and(q->q.eq("sku_sn",term).or().eq("barcode_sn",term).or().likeRight("goods_name",term));
        }
        return success(goodsSkuSnInfoService.list(query.orderByAsc("sku_sn").last("LIMIT 100")));
    }

    /**
     * 查询产品信息列表
     */
    @RequiresPermissions("goods:info:list")
    @PostMapping("/list")
    public TableDataInfo list(@RequestBody GoodsSkuSnInfo goodsSkuSnInfo)
    {
        startPage();
        List<GoodsSkuSnInfo> list = goodsSkuSnInfoService.selectGoodsSkuSnInfoList(goodsSkuSnInfo);
        return getDataTable(list);
    }

    /**
     * 导出产品信息列表
     */
    @RequiresPermissions("goods:info:export")
    @Log(title = "产品信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, GoodsSkuSnInfo goodsSkuSnInfo)
    {
        List<GoodsSkuSnInfo> list = goodsSkuSnInfoService.selectGoodsSkuSnInfoList(goodsSkuSnInfo);
        ExcelUtil<GoodsSkuSnInfo> util = new ExcelUtil<GoodsSkuSnInfo>(GoodsSkuSnInfo.class);
        util.exportExcel(response, list, "产品信息数据");
    }

    /**
     * 获取产品信息详细信息
     */
    @RequiresPermissions("goods:info:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(goodsSkuSnInfoService.selectGoodsSkuSnInfoById(id));
    }

    /**
     * 新增产品信息
     */
    @RequiresPermissions("goods:info:add")
    @Log(title = "产品信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody GoodsSkuSnInfo goodsSkuSnInfo)
    {
        return toAjax(goodsSkuSnInfoService.insertGoodsSkuSnInfo(goodsSkuSnInfo));
    }

    /**
     * 修改产品信息
     */
    @RequiresPermissions("goods:info:edit")
    @Log(title = "产品信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody GoodsSkuSnInfo goodsSkuSnInfo)
    {
        return toAjax(goodsSkuSnInfoService.updateGoodsSkuSnInfo(goodsSkuSnInfo));
    }

    /**
     * 删除产品信息
     */
    @RequiresPermissions("goods:info:remove")
    @Log(title = "产品信息", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(goodsSkuSnInfoService.deleteGoodsSkuSnInfoByIds(ids));
    }

}

