package com.oms.goods.controller.goods;

import java.util.List;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.goods.model.entity.goods.GoodsCategory;
import com.oms.goods.service.goods.GoodsCategoryService;
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
 * 商品类目Controller
 *
 * @author ruoyi
 * @date 2024-08-12
 */
@RestController
@RequestMapping("/category")
public class GoodsCategoryController extends BaseController
{
    @Resource
    private GoodsCategoryService goodsCategoryService;

    /**
     * 查询商品类目列表
     */
    @RequiresPermissions("goods:category:list")
    @GetMapping("/list")
    public TableDataInfo list(GoodsCategory goodsCategory)
    {
        startPage();
        List<GoodsCategory> list = goodsCategoryService.selectGoodsCategoryList(goodsCategory);
        return getDataTable(list);
    }

    /**
     * 导出商品类目列表
     */
    @RequiresPermissions("goods:category:export")
    @Log(title = "商品类目", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, GoodsCategory goodsCategory)
    {
        List<GoodsCategory> list = goodsCategoryService.selectGoodsCategoryList(goodsCategory);
        ExcelUtil<GoodsCategory> util = new ExcelUtil<GoodsCategory>(GoodsCategory.class);
        util.exportExcel(response, list, "商品类目数据");
    }

    /**
     * 获取商品类目详细信息
     */
    @RequiresPermissions("goods:category:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return success(goodsCategoryService.selectGoodsCategoryById(id));
    }

    /**
     * 新增商品类目
     */
    @RequiresPermissions("goods:category:add")
    @Log(title = "商品类目", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody GoodsCategory goodsCategory,String company_code)
    {
        return toAjax(goodsCategoryService.save(goodsCategory,company_code));
    }

    /**
     * 修改商品类目
     */
    @RequiresPermissions("goods:category:edit")
    @Log(title = "商品类目", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody GoodsCategory goodsCategory)
    {
        return toAjax(goodsCategoryService.updateGoodsCategory(goodsCategory));
    }

    /**
     * 删除商品类目
     */
    @RequiresPermissions("goods:category:remove")
    @Log(title = "商品类目", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(goodsCategoryService.deleteGoodsCategoryByIds(ids));
    }
}

