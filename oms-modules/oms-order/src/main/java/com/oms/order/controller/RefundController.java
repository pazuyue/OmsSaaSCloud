package com.oms.order.controller;
import com.oms.order.service.*;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/refunds") public class RefundController {
    @Resource RefundService refunds;
    @Resource GiftService gifts;
    @Resource RefundSync sync;
    @Resource RefundWorkspace workspace;
    @Resource RefundGoodsService goods;
    @Resource RefundBundleReturns bundleReturns;
    @Resource RefundParcelWorkspace parcels;
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/parcels") public AjaxResult parcels(@PathVariable long id){return AjaxResult.success(parcels.list(company(),id));}
    @RequiresPermissions("order:refund:goods") @GetMapping("/business/{id}/parcels/candidates") public AjaxResult candidates(@PathVariable long id,@RequestParam String carrier,@RequestParam String waybill){return AjaxResult.success(parcels.candidates(company(),id,carrier,waybill));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/plans/{plan}") public AjaxResult parcelAttach(@PathVariable long id,@PathVariable long plan,@RequestBody Map<String,Object> body){return AjaxResult.success(parcels.attach(company(),id,plan,body,actor()));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/{parcel}/transport") public AjaxResult parcelTransport(@PathVariable long id,@PathVariable long parcel,@RequestBody Map<String,Object> body){return AjaxResult.success(parcels.transport(company(),id,parcel,body,actor()));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/{parcel}/receipts/{event}/automatic") public AjaxResult automaticReceipt(@PathVariable long id,@PathVariable long parcel,@PathVariable long event,@RequestBody Map<String,Object> input){return AjaxResult.success(parcels.evaluateAutomatic(company(),id,parcel,event,input,actor()));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/{parcel}/receipts/{event}") public AjaxResult parcelReceipt(@PathVariable long id,@PathVariable long parcel,@PathVariable long event,@RequestBody Map<String,Object> body){return AjaxResult.success(parcels.allocate(company(),id,parcel,event,body,actor()));}
    @Resource RefundParcelReview parcelReview;
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/parcels/plans/{plan}/review") public AjaxResult parcelReview(@PathVariable long id,@PathVariable long plan){return AjaxResult.success(parcelReview.context(company(),id,plan));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/plans/{plan}/review") public AjaxResult reviewParcel(@PathVariable long id,@PathVariable long plan,@RequestBody Map<String,Object> body){String c=company();parcelReview.decide(c,id,plan,body,actor());if("CORRECT_LOGISTICS".equals(OrderValues.text(body,"decision")))parcelAutomation.process(c,id);return AjaxResult.success(goods.report(c,id));}
    @Resource RefundParcelPolicy parcelPolicy;
    @Resource RefundParcelAutomation parcelAutomation;
    @RequiresPermissions("order:refund:config") @GetMapping("/parcel-configs") public AjaxResult parcelConfigs(@RequestParam Map<String,String> query){return AjaxResult.success(parcelPolicy.list(company(),query));}
    @RequiresPermissions("order:refund:config") @GetMapping("/parcel-configs/catalog") public AjaxResult parcelConfigShops(){return AjaxResult.success(catalog.channels(company()));}
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/parcels/policy") public AjaxResult parcelPolicy(@PathVariable long id){String c=company();return AjaxResult.success(parcelPolicy.forRefund(c,id));}
    @RequiresPermissions("order:refund:config") @GetMapping("/parcel-configs/{channel}") public AjaxResult parcelConfig(@PathVariable long channel){return AjaxResult.success(parcelPolicy.detail(company(),channel));}
    @RequiresPermissions("order:refund:config") @PostMapping("/parcel-configs/{channel}") public AjaxResult saveParcelConfig(@PathVariable long channel,@RequestBody Map<String,Object> body){String c=company();parcelPolicy.save(c,channel,body,actor());sync.kick(c);return AjaxResult.success();}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/auto-run") public AjaxResult autoParcels(@PathVariable long id){parcelAutomation.process(company(),id);return AjaxResult.success(goods.report(company(),id));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/plans/{plan}/logistics") public AjaxResult manualParcelLogistics(@PathVariable long id,@PathVariable long plan,@RequestBody Map<String,Object> body){parcelAutomation.manual(company(),id,plan,body,actor());return AjaxResult.success(goods.report(company(),id));}
    @Resource OrderCatalog catalog;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    private String actor(){return SecurityUtils.getUsername();}
    @RequiresPermissions("order:refund:list") @GetMapping("/catalog") public AjaxResult catalog(){return AjaxResult.success(catalog.channels(company()));}
    @RequiresPermissions("order:refund:list") @GetMapping("/platform") public AjaxResult platform(@RequestParam Map<String,String> q){return AjaxResult.success(refunds.platformList(company(),q));}
    @RequiresPermissions("order:refund:query") @GetMapping("/platform/{id}") public AjaxResult platformDetail(@PathVariable long id){return AjaxResult.success(refunds.platformDetail(company(),id));}
    @RequiresPermissions("order:refund:query") @GetMapping("/platform/{id}/snapshots/{snapshot}") public AjaxResult snapshot(@PathVariable long id,@PathVariable long snapshot){return AjaxResult.success(refunds.snapshot(company(),id,snapshot));}
    @RequiresPermissions("order:refund:convert") @PostMapping("/platform/{id}/convert") public AjaxResult convert(@PathVariable long id){return AjaxResult.success(refunds.requestConvert(company(),id,actor()));}
    @RequiresPermissions("order:refund:list") @GetMapping("/business") public AjaxResult business(@RequestParam Map<String,String> q){return AjaxResult.success(refunds.businessList(company(),q));}
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(workspace.detail(company(),id));}
    @RequiresPermissions("order:refund:review") @PostMapping("/business/{id}/review") public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();Object result=workspace.review(c,id,body,actor());parcelAutomation.process(c,id);return AjaxResult.success(result);}
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/goods") public AjaxResult goods(@PathVariable long id){return AjaxResult.success(goods.report(company(),id));}
    @RequiresPermissions("order:refund:goods") @GetMapping("/business/{id}/goods/options") public AjaxResult goodsOptions(@PathVariable long id){return AjaxResult.success(goods.options(company(),id));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/bundle-plans") public AjaxResult bundlePlan(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();Object result=bundleReturns.create(c,id,body,actor());parcelAutomation.process(c,id);return AjaxResult.success(result);}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/plans") public AjaxResult plan(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();Object result=goods.create(c,id,body,actor());parcelAutomation.process(c,id);return AjaxResult.success(result);}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/refresh") public AjaxResult refreshGoods(@PathVariable long id){goods.refresh(company(),id);return AjaxResult.success(goods.report(company(),id));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/plans/{plan}/documents/{document}/{action}") public AjaxResult goodsAction(@PathVariable long id,@PathVariable long plan,@PathVariable long document,@PathVariable String action,@RequestBody Map<String,Object> body){if("waive".equals(action)){com.ruoyi.common.security.auth.AuthUtil.checkPermi("order:refund:close");if(gifts.isGiftPlan(company(),id,plan))com.ruoyi.common.security.auth.AuthUtil.checkPermi("order:gift:waive");}return AjaxResult.success(goods.action(company(),id,plan,document,action,body,actor()));}
    @RequiresPermissions("order:refund:close") @PostMapping("/business/{id}/goods/close-withdrawn") public AjaxResult closeWithdrawn(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(goods.closeWithdrawn(company(),id,body,actor()));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/finish") public AjaxResult finishGoods(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(goods.finish(company(),id,body,actor()));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/cancel-unshipped") public AjaxResult cancelUnshipped(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(goods.cancelUnshipped(company(),id,body,actor()));}
    @RequiresPermissions("order:refund:close") @PostMapping("/business/{id}/goods/waive") public AjaxResult waiveGoods(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(goods.waiveAll(company(),id,body,actor()));}
    @RequiresPermissions("order:refund:query") @GetMapping("/orders/{id}") public AjaxResult records(@PathVariable long id){return AjaxResult.success(workspace.records(company(),id));}
    @RequiresPermissions("order:refund:config") @GetMapping("/configs") public AjaxResult configurations(){return AjaxResult.success(sync.configurations(company()));}
    @RequiresPermissions("order:refund:config") @PostMapping("/configs") public AjaxResult save(@RequestBody Map<String,Object> body){String c=company();sync.save(c,body,actor());sync.kick(c);return AjaxResult.success();}
    @RequiresPermissions("order:refund:config") @PostMapping("/configs/{channel}/test") public AjaxResult test(@PathVariable long channel){sync.test(company(),channel);return AjaxResult.success("退款推送库结构检查通过");}
    @RequiresPermissions("order:refund:sync") @PostMapping("/sync") public AjaxResult submit(@RequestBody Map<String,Object> body){String c=company();Map<String,Object> result=sync.submit(c,body,actor());sync.kick(c);return AjaxResult.success(result);}
    @RequiresPermissions("order:refund:sync") @GetMapping("/tasks") public AjaxResult tasks(){return AjaxResult.success(sync.tasks(company()));}
    @RequiresPermissions("order:refund:sync") @GetMapping("/tasks/{id}") public AjaxResult task(@PathVariable long id){return AjaxResult.success(sync.task(company(),id));}
    @RequiresPermissions("order:refund:sync") @PostMapping("/tasks/{id}/retry") public AjaxResult retry(@PathVariable long id){String c=company();sync.retry(c,id);sync.kick(c);return AjaxResult.success();}
    @InnerAuth @PostMapping("/internal/scan-all") public R<Map<String,Object>> scan(){return R.ok(sync.poll());}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/{parcel}/document/{action}") public AjaxResult parcelDocument(@PathVariable long id,@PathVariable long parcel,@PathVariable String action,@RequestBody Map<String,Object> input){return AjaxResult.success(parcels.documentAction(company(),id,parcel,action,input,actor()));}

}
