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
    @Resource RefundConfiguration configuration;
    @RequiresPermissions("order:refund:config") @GetMapping("/settings") public AjaxResult settings(@RequestParam Map<String,String> query){return AjaxResult.success(configuration.list(company(),query));}
    @RequiresPermissions("order:refund:config") @GetMapping("/settings/{channel}") public AjaxResult setting(@PathVariable long channel){return AjaxResult.success(configuration.detail(company(),channel));}
    @RequiresPermissions("order:refund:config") @PostMapping("/settings/{channel}/api-sync") public AjaxResult apiSync(@PathVariable long channel,@RequestBody Map<String,Object> body){String c=company();flow.saveApiSync(c,channel,body);sync.kick(c);return AjaxResult.success();}
    @Resource RefundService refunds;
    @Resource SharedReturnWorkspace sharedReturns;
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/shared-returns/{plan}") public AjaxResult sharedReturn(@PathVariable long id,@PathVariable long plan){return AjaxResult.success(sharedReturns.report(company(),"REFUND",id,plan));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/shared-returns/{plan}/{action}") public AjaxResult sharedReturnAction(@PathVariable long id,@PathVariable long plan,@PathVariable String action,@RequestBody Map<String,Object> body){return AjaxResult.success(sharedReturns.action(company(),"REFUND",id,plan,action,body,SecurityUtils.getLoginUser().getPermissions(),actor()));}
    @Resource RefundPlatformExecution platformExecution;
    @Resource RefundFlowAutomation flow;
    @Resource RefundMoneyAuthorizations moneyAuthorizations;
    @RequiresPermissions("order:refund:config") @GetMapping("/flow-configs/{channel}/money-authorizations") public AjaxResult moneyAuthorizations(@PathVariable long channel){return AjaxResult.success(moneyAuthorizations.list(company(),channel));}
    @RequiresPermissions("order:refund:config") @PostMapping("/flow-configs/{channel}/money-authorizations") public AjaxResult createMoneyAuthorization(@PathVariable long channel,@RequestBody Map<String,Object> body){return AjaxResult.success(moneyAuthorizations.create(company(),channel,body,actor()));}
    @RequiresPermissions("order:refund:config") @PostMapping("/flow-configs/{channel}/money-authorizations/{id}/revoke") public AjaxResult revokeMoneyAuthorization(@PathVariable long channel,@PathVariable long id,@RequestBody Map<String,Object> body){moneyAuthorizations.revoke(company(),channel,id,Long.parseLong(String.valueOf(body.get("control_version"))),actor());return AjaxResult.success();}
    @RequiresPermissions("order:refund:config") @GetMapping("/flow-configs/{channel}/addresses") public AjaxResult flowAddresses(@PathVariable long channel){String c=company();catalog.validateShop(c,channel);return AjaxResult.success(platformExecution.addresses(c,channel,actor()));}
    @RequiresPermissions("order:refund:config") @GetMapping("/flow-configs/{channel}") public AjaxResult flowPolicy(@PathVariable long channel){return AjaxResult.success(flow.policy(company(),channel));}
    @RequiresPermissions("order:refund:config") @PostMapping("/flow-configs/{channel}") public AjaxResult saveFlow(@PathVariable long channel,@RequestBody Map<String,Object> body){String c=company();flow.save(c,channel,body,actor());sync.kick(c);return AjaxResult.success();}
    @RequiresPermissions("order:refund:execute") @PostMapping("/business/{id}/flow/retry") public AjaxResult retryFlow(@PathVariable long id){String c=company();flow.retry(c,id);sync.kick(c);return AjaxResult.success();}

    @RequiresPermissions("order:refund:query") @PostMapping("/business/{id}/platform/refresh") public AjaxResult refreshPlatform(@PathVariable long id){return AjaxResult.success(platformExecution.inspect(company(),id,actor()));}
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/platform/information/{action}") public AjaxResult platformInformation(@PathVariable long id,@PathVariable String action,@RequestParam Map<String,String> query){return AjaxResult.success(platformExecution.information(company(),id,action,query));}
    @RequiresPermissions("order:refund:execute") @PostMapping("/business/{id}/platform/actions/{action}") public AjaxResult platformAction(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> body){return AjaxResult.success(platformExecution.submit(company(),id,action,body,actor()));}

    @Resource GiftService gifts;
    @Resource RefundSync sync;
    @Resource RefundWorkspace workspace;
    @Resource RefundGoodsService goods;
    @Resource RefundGoodsTasks goodsTasks;
    @Resource RefundDispositionService disposition;
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/disposition") public AjaxResult disposition(@PathVariable long id){return AjaxResult.success(disposition.report(company(),id));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/tasks/{task}/retry") public AjaxResult retryGoodsTask(@PathVariable long id,@PathVariable long task){String c=company();goodsTasks.retry(c,id,task);sync.kick(c);return AjaxResult.success();}
    @Resource RefundReturnAutoCreate autoCreate;
    @Resource RefundBundleReturns bundleReturns;
    @Resource RefundParcelWorkspace parcels;
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/parcels") public AjaxResult parcels(@PathVariable long id){return AjaxResult.success(parcels.list(company(),id));}
    @RequiresPermissions("order:refund:goods") @GetMapping("/business/{id}/parcels/candidates") public AjaxResult candidates(@PathVariable long id,@RequestParam String carrier,@RequestParam String waybill){return AjaxResult.success(parcels.candidates(company(),id,carrier,waybill));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/plans/{plan}") public AjaxResult parcelAttach(@PathVariable long id,@PathVariable long plan,@RequestBody Map<String,Object> body){String c=company();Object result=parcels.attach(c,id,plan,body,actor());sync.kick(c);return AjaxResult.success(result);}
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
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/parcels/auto-run") public AjaxResult autoParcels(@PathVariable long id){goods.retryAutoDispatch(company(),id);parcelAutomation.process(company(),id);return AjaxResult.success(goods.report(company(),id));}
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
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/amount-records") public AjaxResult amountRecords(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return AjaxResult.success(workspace.amountRecords(company(),id,pageNum,pageSize));}
    @RequiresPermissions("order:refund:review") @PostMapping("/business/{id}/review") public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();Object result=workspace.review(c,id,body,actor());sync.kick(c);parcelAutomation.process(c,id);return AjaxResult.success(result);}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/auto-create/retry") public AjaxResult retryAutoCreate(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();autoCreate.retry(c,id,body);sync.kick(c);return AjaxResult.success();}
    @RequiresPermissions("order:refund:query") @GetMapping("/business/{id}/goods") public AjaxResult goods(@PathVariable long id){return AjaxResult.success(goods.report(company(),id));}
    @RequiresPermissions("order:refund:goods") @GetMapping("/business/{id}/goods/options") public AjaxResult goodsOptions(@PathVariable long id){return AjaxResult.success(goods.options(company(),id));}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/bundle-plans") public AjaxResult bundlePlan(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();Object result=bundleReturns.create(c,id,body,actor());parcelAutomation.process(c,id);return AjaxResult.success(result);}
    @RequiresPermissions("order:refund:goods") @PostMapping("/business/{id}/goods/plans") public AjaxResult plan(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();Object result=goods.create(c,id,body,actor());sync.kick(c);return AjaxResult.success(result);}
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
