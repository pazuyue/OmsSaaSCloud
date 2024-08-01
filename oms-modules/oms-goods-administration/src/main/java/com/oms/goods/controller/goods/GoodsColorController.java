package com.oms.goods.controller.goods;

import java.util.List;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;

import com.oms.goods.model.entity.goods.GoodsColor;
import com.oms.goods.service.goods.GoodsColorService;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 商品颜色Controller
 *
 * @author ruoyi
 * @date 2024-07-31
 */
@RestController
@RequestMapping("/color")
public class GoodsColorController extends BaseController
{
    @Autowired
    private GoodsColorService goodsColorService;

    /**
     * 查询商品颜色列表
     */
    @RequiresPermissions("goods:color:list")
    @GetMapping("/list")
    public TableDataInfo list(GoodsColor goodsColor)
    {
        startPage();
        List<GoodsColor> list = goodsColorService.selectGoodsColorList(goodsColor);
        return getDataTable(list);
    }

    /**
     * 导出商品颜色列表
     */
    @RequiresPermissions("goods:color:export")
    @Log(title = "商品颜色", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, GoodsColor goodsColor)
    {
        List<GoodsColor> list = goodsColorService.selectGoodsColorList(goodsColor);
        ExcelUtil<GoodsColor> util = new ExcelUtil<GoodsColor>(GoodsColor.class);
        util.exportExcel(response, list, "商品颜色数据");
    }

    /**
     * 获取商品颜色详细信息
     */
    @RequiresPermissions("oms:color:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(goodsColorService.selectGoodsColorById(id));
    }

    /**
     * 新增商品颜色
     */
    @RequiresPermissions("goods:color:add")
    @Log(title = "商品颜色", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody GoodsColor goodsColor)
    {
        return toAjax(goodsColorService.insertGoodsColor(goodsColor));
    }

    /**
     * 修改商品颜色
     */
    @RequiresPermissions("goods:color:edit")
    @Log(title = "商品颜色", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody GoodsColor goodsColor)
    {
        return toAjax(goodsColorService.updateGoodsColor(goodsColor));
    }

    /**
     * 删除商品颜色
     */
    @RequiresPermissions("goods:color:remove")
    @Log(title = "商品颜色", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(goodsColorService.deleteGoodsColorByIds(ids));
    }
}

