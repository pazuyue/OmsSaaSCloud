package com.oms.goods.controller.goods;

import java.util.List;
import java.io.IOException;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.goods.model.entity.goods.GoodsSize;
import com.oms.goods.service.goods.GoodsSizeService;
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
 * 商品尺码Controller
 *
 * @author ruoyi
 * @date 2024-07-30
 */
@RestController
@RequestMapping("/size")
public class GoodsSizeController extends BaseController
{
    @Resource
    private GoodsSizeService goodsSizeService;

    /**
     * 查询商品尺码列表
     */
    @RequiresPermissions("oms:size:list")
    @GetMapping("/list")
    public TableDataInfo list(GoodsSize goodsSize,String company_code)
    {
        startPage();
        if (company_code != null)
            goodsSize.setCompanyCode(company_code);
        List<GoodsSize> list = goodsSizeService.selectGoodsSizeList(goodsSize);
        return getDataTable(list);
    }

    /**
     * 导出商品尺码列表
     */
    @RequiresPermissions("oms:size:export")
    @Log(title = "商品尺码", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, GoodsSize goodsSize)
    {
        List<GoodsSize> list = goodsSizeService.selectGoodsSizeList(goodsSize);
        ExcelUtil<GoodsSize> util = new ExcelUtil<GoodsSize>(GoodsSize.class);
        util.exportExcel(response, list, "商品尺码数据");
    }

    /**
     * 获取商品尺码详细信息
     */
    @RequiresPermissions("oms:size:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(goodsSizeService.selectGoodsSizeById(id));
    }

    /**
     * 新增商品尺码
     */
    @RequiresPermissions("oms:size:add")
    @Log(title = "商品尺码", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody GoodsSize goodsSize)
    {
        return toAjax(goodsSizeService.insertGoodsSize(goodsSize));
    }

    /**
     * 修改商品尺码
     */
    @RequiresPermissions("oms:size:edit")
    @Log(title = "商品尺码", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody GoodsSize goodsSize)
    {
        return toAjax(goodsSizeService.updateGoodsSize(goodsSize));
    }

    /**
     * 删除商品尺码
     */
    @RequiresPermissions("oms:size:remove")
    @Log(title = "商品尺码", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(goodsSizeService.deleteGoodsSizeByIds(ids));
    }
}

