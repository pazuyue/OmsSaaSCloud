package com.oms.goods.controller.goods;
import com.oms.goods.service.goods.GoodsCompany;
import com.oms.goods.service.goods.impl.GoodsWorkspaceService;
import com.oms.goods.model.vo.export.GoodsExportRow;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;
import com.oms.goods.model.entity.goods.GoodsCategory;
@RestController @RequestMapping("/category")
public class GoodsCategoryController extends BaseController {
    @Resource private GoodsWorkspaceService workspace;
    @RequiresPermissions("goods:category:list") @GetMapping("/list")
    public TableDataInfo list(@RequestParam Map<String,Object> filter,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {
        return workspace.masterPage(GoodsCompany.current(),"category",filter,pageNum,pageSize);
    }
    @RequiresPermissions("goods:category:export") @PostMapping("/export")
    @Log(title="商品分类",businessType=BusinessType.EXPORT)
    public void export(HttpServletResponse response,@RequestParam Map<String,Object> filter) {
        List<GoodsCategory> rows=workspace.masterList(GoodsCompany.current(),"category",filter).stream().map(r->workspace.convert(r,GoodsCategory.class)).collect(Collectors.toList());
        new ExcelUtil<>(GoodsCategory.class).exportExcel(response,rows,"商品分类");
    }
    @RequiresPermissions("goods:category:query") @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable long id){return success(workspace.masterDetail(GoodsCompany.current(),"category",id));}
    @RequiresPermissions("goods:category:add") @PostMapping
    @Log(title="商品分类",businessType=BusinessType.INSERT)
    public AjaxResult add(@RequestBody Map<String,Object> data){
        if(data.get("id")!=null)throw new IllegalArgumentException("新增不能指定 ID");
        return success(workspace.saveMaster(GoodsCompany.current(),"category",data));
    }
    @RequiresPermissions("goods:category:edit") @PutMapping
    @Log(title="商品分类",businessType=BusinessType.UPDATE)
    public AjaxResult edit(@RequestBody Map<String,Object> data){
        if(data.get("id")==null || Long.parseLong(String.valueOf(data.get("id")))<=0)throw new IllegalArgumentException("请选择要修改的资料");
        return success(workspace.saveMaster(GoodsCompany.current(),"category",data));
    }
    @RequiresPermissions("goods:category:remove") @DeleteMapping("/{ids}")
    @Log(title="商品分类",businessType=BusinessType.DELETE)
    public AjaxResult remove(@PathVariable List<Long> ids){return toAjax(workspace.deleteMaster(GoodsCompany.current(),"category",ids));}
}
