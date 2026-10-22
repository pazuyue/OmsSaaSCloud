package com.oms.order.controller;

import com.oms.order.service.*;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/gifts") public class GiftController {
    @Resource GiftService gifts;
    @Resource GiftConfiguration configuration;
    @Resource GiftStrategyDesigner designer;
    @Resource OrderCatalog catalog;
    @Resource RefundGoodsService goods;
    @Resource RefundParcelAutomation parcels;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    @RequiresPermissions("order:gift:list") @GetMapping("/groups") public AjaxResult groups(){return AjaxResult.success(gifts.groups(company()));}
    @RequiresPermissions("order:gift:config") @PostMapping("/groups") public AjaxResult save(@RequestBody Map<String,Object> body){return AjaxResult.success(gifts.save(company(),body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:gift:list") @GetMapping("/shop-groups") public AjaxResult shopGroups(){return AjaxResult.success(configuration.scopes(company()));}
    @RequiresPermissions("order:gift:config") @PostMapping("/shop-groups") public AjaxResult saveShopGroup(@RequestBody Map<String,Object> body){Map<String,Object> saved=configuration.save(company(),body,SecurityUtils.getUsername());gifts.invalidate(company());return AjaxResult.success(saved);}
    @RequiresPermissions("order:gift:list") @GetMapping("/shops/{channel}/effective") public AjaxResult effective(@PathVariable long channel){return AjaxResult.success(configuration.effective(company(),channel,gifts.groups(company())));}
    @RequiresPermissions("order:gift:list") @GetMapping("/catalog") public AjaxResult catalog(){return AjaxResult.success(catalog.channels(company()));}
    @RequiresPermissions("order:gift:config") @PostMapping("/goods-search") public AjaxResult search(@RequestBody Map<String,Object> query){return AjaxResult.success(catalog.searchGoods(company(),query));}
    @RequiresPermissions("order:gift:config") @PostMapping("/goods-resolve") public AjaxResult resolve(@RequestBody Map<String,Object> body){return AjaxResult.success(designer.resolve(company(),body));}
    @RequiresPermissions("order:gift:config") @PostMapping("/simulate") public AjaxResult simulate(@RequestBody Map<String,Object> body){return AjaxResult.success(designer.simulate(company(),body));}
    @RequiresPermissions("order:gift:query") @GetMapping("/records") public AjaxResult records(@RequestParam Map<String,String> query){return AjaxResult.success(gifts.records(company(),query));}
    @RequiresPermissions("order:gift:query") @GetMapping("/orders/{order}") public AjaxResult report(@PathVariable long order){return AjaxResult.success(gifts.report(company(),order));}
    @RequiresPermissions("order:gift:query") @PostMapping("/preview") public AjaxResult previewNumber(@RequestBody Map<String,Object> body){return AjaxResult.success(gifts.previewNumber(company(),OrderValues.text(body,"number")));}
    @RequiresPermissions("order:gift:query") @PostMapping("/orders/{order}/preview") public AjaxResult preview(@PathVariable long order){return AjaxResult.success(gifts.preview(company(),order));}
    @RequiresPermissions("order:gift:execute") @PostMapping("/orders/{order}/cancel") public AjaxResult cancel(@PathVariable long order,@RequestBody Map<String,Object> body){return AjaxResult.success(gifts.cancel(company(),order,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:gift:waive") @PostMapping("/orders/{order}/waive") public AjaxResult waive(@PathVariable long order,@RequestBody Map<String,Object> body){return AjaxResult.success(gifts.waive(company(),order,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:gift:execute") @PostMapping("/refunds/{refund}/return") public AjaxResult returns(@PathVariable long refund,@RequestBody Map<String,Object> body){OrderValues.require(OrderValues.number(body,"gift_plan_id")>0,"缺少赠品计划身份");String c=company();Object result=goods.create(c,refund,body,SecurityUtils.getUsername());parcels.process(c,refund);return AjaxResult.success(result);}
    @RequiresPermissions("order:gift:execute") @GetMapping("/refunds/{refund}/options") public AjaxResult returnOptions(@PathVariable long refund){return AjaxResult.success(goods.options(company(),refund));}
    @RequiresPermissions("order:gift:execute") @PostMapping("/refunds/{refund}/plans/{plan}/documents/{document}/{action}") public AjaxResult returnAction(@PathVariable long refund,@PathVariable long plan,@PathVariable long document,@PathVariable String action,@RequestBody Map<String,Object> body){String c=company();gifts.requireGiftPlan(c,refund,plan);OrderValues.require(Arrays.asList("dispatch","query","posting","cancel","stop","rearrange","review","retry").contains(action),"请从赠品处理入口核对本退回单");return AjaxResult.success(goods.action(c,refund,plan,document,action,body,SecurityUtils.getUsername()));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
