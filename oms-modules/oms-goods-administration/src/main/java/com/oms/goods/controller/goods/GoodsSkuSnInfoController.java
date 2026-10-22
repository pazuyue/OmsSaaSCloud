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

@RestController @RequestMapping("/info")
public class GoodsSkuSnInfoController extends BaseController {
    @Resource private GoodsWorkspaceService workspace;
    @Resource private com.oms.goods.service.goods.impl.GoodsLuckyBagService luckyBags;
    @RequiresPermissions(value={"goods:info:query","goods:info:edit"},logical=Logical.OR) @GetMapping("/{id}/lucky-bag")
    public AjaxResult luckyBag(@PathVariable long id){return success(luckyBags.detail(GoodsCompany.current(),id));}
    @RequiresPermissions("goods:info:edit") @PostMapping("/{id}/lucky-bag/{action}")
    public AjaxResult luckyBagSave(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> input){if(!Arrays.asList("draft","publish").contains(action))throw new IllegalArgumentException("福袋规则动作无效");return success(luckyBags.save(GoodsCompany.current(),id,input,SecurityUtils.getUsername(),"publish".equals(action)));}
    /** Bounded inventory lookup: one request for a page of SKUs, or a small search result. */
    @RequiresPermissions(value={"wmsInventory:inventory:list","channelInventory:inventory:list"},logical=com.ruoyi.common.security.annotation.Logical.OR)
    @GetMapping("/inventoryLookup")
    public AjaxResult inventoryLookup(@RequestParam(required=false) List<String> skus,
                                      @RequestParam(required=false) String keyword) {
        return success(workspace.inventoryLookup(GoodsCompany.current(), skus, keyword));
    }


    @RequiresPermissions("goods:info:list") @PostMapping("/list")
    public TableDataInfo list(@RequestBody Map<String,Object> filter,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {
        return workspace.goodsPage(GoodsCompany.current(),filter,pageNum,pageSize);
    }
    @RequiresPermissions(value={"goods:info:list","goods:info:add","goods:info:edit","goods:info:import"},logical=Logical.OR)
    @GetMapping("/options")
    public AjaxResult options(){return success(workspace.options(GoodsCompany.current()));}
    @RequiresPermissions("goods:info:export") @PostMapping("/export")
    @Log(title="商品资料",businessType=BusinessType.EXPORT)
    public void export(HttpServletResponse response,@RequestParam Map<String,Object> filter) {
        List<GoodsExportRow> rows=workspace.goodsExport(GoodsCompany.current(),filter).stream().map(r->workspace.convert(r,GoodsExportRow.class)).collect(Collectors.toList());
        new ExcelUtil<>(GoodsExportRow.class).exportExcel(response,rows,"商品资料");
    }
    @RequiresPermissions(value={"goods:info:query","goods:info:edit"},logical=Logical.OR) @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable long id){return success(workspace.goodsDetail(GoodsCompany.current(),id));}
    @RequiresPermissions(value={"goods:info:query","goods:info:edit"},logical=Logical.OR) @GetMapping("/{id}/gift-references")
    public AjaxResult giftReferences(@PathVariable long id){return success(workspace.giftReferences(GoodsCompany.current(),id));}
    @RequiresPermissions("goods:info:add") @PostMapping
    @Log(title="商品资料",businessType=BusinessType.INSERT)
    public AjaxResult add(@RequestBody Map<String,Object> data) {
        if(data.get("id")!=null)throw new IllegalArgumentException("新增商品不能指定 ID");
        return success(workspace.saveGoods(GoodsCompany.current(),data,SecurityUtils.getUsername()));
    }
    @RequiresPermissions("goods:info:edit") @PutMapping
    @Log(title="商品资料",businessType=BusinessType.UPDATE)
    public AjaxResult edit(@RequestBody Map<String,Object> data) {
        if(data.get("id")==null || Long.parseLong(String.valueOf(data.get("id")))<=0)throw new IllegalArgumentException("请选择要修改的商品");
        return success(workspace.saveGoods(GoodsCompany.current(),data,SecurityUtils.getUsername()));
    }
    @RequiresPermissions("goods:info:remove") @DeleteMapping("/{ids}")
    @Log(title="商品资料",businessType=BusinessType.DELETE)
    public AjaxResult remove(@PathVariable List<Long> ids){return toAjax(workspace.deleteGoods(GoodsCompany.current(),ids));}
}
