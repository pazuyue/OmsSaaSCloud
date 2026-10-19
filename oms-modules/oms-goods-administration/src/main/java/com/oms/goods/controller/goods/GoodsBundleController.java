package com.oms.goods.controller.goods;
import com.oms.goods.service.goods.GoodsCompany;
import com.oms.goods.service.goods.impl.GoodsBundleService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/info/{id}/bundle") public class GoodsBundleController extends BaseController {
 @Resource GoodsBundleService bundles;
 @RequiresPermissions("goods:info:query") @GetMapping public AjaxResult detail(@PathVariable long id){return success(bundles.detail(GoodsCompany.current(),id));}
 @RequiresPermissions("goods:info:edit") @PostMapping public AjaxResult save(@PathVariable long id,@RequestBody Map<String,Object> input){return success(bundles.save(GoodsCompany.current(),id,input,SecurityUtils.getUsername()));}
}
