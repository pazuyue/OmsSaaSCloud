package com.oms.order.controller;
import com.oms.order.service.*;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/exchanges")
public class ExchangeController {
    @Resource private ExchangeService exchanges;
    @Resource private ExchangeFlowPolicy automaticPolicy;
    @Resource private ExchangeFlowAutomation automaticFlow;
    @Resource private SharedReturnWorkspace sharedReturns;
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}/shared-return") public AjaxResult sharedReturn(@PathVariable long id){return AjaxResult.success(sharedReturns.report(company(),"EXCHANGE",id,id));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/shared-return/{action}") public AjaxResult sharedAction(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> body){return AjaxResult.success(sharedReturns.action(company(),"EXCHANGE",id,id,action,body,SecurityUtils.getLoginUser().getPermissions(),SecurityUtils.getUsername()));}
    @Resource private ExchangeSync sync;
    @Resource private OrderCatalog catalog;
    @Resource private ExchangeFulfillment fulfillment;
    @Resource private ExchangeExecution execution;
    @Resource private OrderAllocationService allocation;
    @Resource private ExchangeWorkspace workspace;
    @Resource private ExchangeContinuationService continuations;
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}/continuation-preview") public AjaxResult continuationPreview(@PathVariable long id){return AjaxResult.success(continuations.preview(company(),id));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/continuations") public AjaxResult continuationSubmit(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(continuations.submit(company(),id,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:review") @PostMapping("/{id}/continuations/{round}/review") public AjaxResult continuationReview(@PathVariable long id,@PathVariable long round,@RequestBody Map<String,Object> body){String c=company();Map<String,Object> result=continuations.review(c,id,round,body,SecurityUtils.getUsername());if("APPROVE".equals(OrderValues.text(body,"decision")))sync.kick(c);return AjaxResult.success(result);}
    @Resource private ExchangeControlService controls;
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}/controls") public AjaxResult controls(@PathVariable long id){return AjaxResult.success(controls.report(company(),id));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/control/{action}") public AjaxResult control(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> body){String c=company(),actor=SecurityUtils.getUsername();switch(action){case "request":return AjaxResult.success(controls.request(c,id,body,actor));case "hold":return AjaxResult.success(controls.hold(c,id,body,actor));case "resume":return AjaxResult.success(controls.resume(c,id,body,actor));case "recheck":return AjaxResult.success(controls.recheck(c,id,body,actor));case "close":return AjaxResult.success(controls.close(c,id,body,actor));default:throw new IllegalArgumentException("换货控制动作无效");}}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/return-documents/{document}/{action}") public AjaxResult returnDocument(@PathVariable long id,@PathVariable long document,@PathVariable String action,@RequestBody Map<String,Object> body){return AjaxResult.success(returnWorkspace.document(company(),id,document,action,body,SecurityUtils.getUsername()));}
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    @RequiresPermissions("order:exchange:list") @GetMapping public TableDataInfo list(@RequestParam(defaultValue="") String number,@RequestParam(required=false) Long channelId,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String problem,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return exchanges.list(company(),number,channelId,state,kind,problem,pageNum,pageSize);}
    @RequiresPermissions("order:exchange:config") @GetMapping("/flow-configs/{channel}") public AjaxResult flowConfig(@PathVariable long channel){return AjaxResult.success(automaticPolicy.read(company(),channel));}
    @RequiresPermissions("order:exchange:config") @PostMapping("/flow-configs/{channel}") public AjaxResult flowSave(@PathVariable long channel,@RequestBody Map<String,Object> body){automaticPolicy.save(company(),channel,body,SecurityUtils.getUsername());return AjaxResult.success(automaticPolicy.read(company(),channel));}
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}/automatic-flow") public AjaxResult automaticReport(@PathVariable long id){return AjaxResult.success(automaticFlow.report(company(),id));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/automatic-flow/recheck") public AjaxResult automaticRecheck(@PathVariable long id,@RequestBody Map<String,Object> body){OrderValues.require(OrderValues.number(exchanges.detail(company(),id),"control_version")==OrderValues.number(body,"control_version"),"单据已变化，请刷新后重新评估");automaticFlow.retry(company(),id);return AjaxResult.success(automaticFlow.report(company(),id));}
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(workspace.detail(company(),id));}
    @RequiresPermissions("order:exchange:list") @GetMapping("/workbench") public AjaxResult workbench(@RequestParam(defaultValue="") String number,@RequestParam(required=false) Long channelId,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String problem,@RequestParam(defaultValue="ALL") String bucket,@RequestParam(defaultValue="") String phase,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return AjaxResult.success(workspace.list(company(),number,channelId,state,kind,problem,bucket,pageNum,pageSize,phase));}
    @RequiresPermissions(value={"order:exchange:review","order:exchange:create"},logical=Logical.OR) @PostMapping("/goods-search") public AjaxResult goods(@RequestBody Map<String,Object> query){return AjaxResult.success(workspace.search(company(),query));}
    @RequiresPermissions("order:exchange:create") @GetMapping("/create-sources") public AjaxResult createSources(@RequestParam long orderId){return AjaxResult.success(workspace.createSources(company(),orderId));}
    @Resource private ExchangeReturnWorkspace returnWorkspace;
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}/return") public AjaxResult returns(@PathVariable long id){return AjaxResult.success(returnWorkspace.report(company(),id));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/return-logistics") public AjaxResult logistics(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(returnWorkspace.logistics(company(),id,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/return/{action}") public AjaxResult returnAction(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> body){return AjaxResult.success(returnWorkspace.action(company(),id,action,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:review") @PostMapping("/{id}/link-platform") public AjaxResult linkPlatform(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(returnWorkspace.link(company(),id,body,SecurityUtils.getUsername()));}
    @RequiresPermissions(value={"order:exchange:query","order:exchange:config","order:exchange:sync"},logical=Logical.OR) @GetMapping("/capability/{channel}") public AjaxResult capability(@PathVariable long channel){return AjaxResult.success(exchanges.capability(company(),channel));}
    @RequiresPermissions("order:exchange:list") @GetMapping("/catalog") public AjaxResult catalog(){Map<String,Object> r=new LinkedHashMap<>();r.put("channels",catalog.channels(company()));r.put("warehouses",catalog.warehouses(company()));return AjaxResult.success(r);}
    @RequiresPermissions("order:exchange:create") @PostMapping public AjaxResult create(@RequestBody Map<String,Object> body){return AjaxResult.success(fulfillment.create(company(),body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}/reasons") public AjaxResult reasons(@PathVariable long id){return AjaxResult.success(execution.reasons(company(),id));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/execute") public AjaxResult execute(@PathVariable long id,@RequestBody Map<String,Object> body){String c=company();Map<String,Object> r=execution.request(c,id,body,SecurityUtils.getUsername());sync.kick(c);return AjaxResult.success(r);}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/reconcile") public AjaxResult reconcile(@PathVariable long id){return AjaxResult.success(execution.reconcile(company(),id));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/advance") public AjaxResult advance(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(execution.advance(company(),id,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/allocate") public AjaxResult allocate(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(allocation.allocateExchange(company(),id,OrderValues.number(body,"control_version"),SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:execute") @PostMapping("/{id}/qc") public AjaxResult qc(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(execution.qc(company(),id,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:review") @PostMapping("/{id}/review") public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(exchanges.review(company(),id,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:sync") @PostMapping("/{id}/refresh") public AjaxResult refresh(@PathVariable long id){return AjaxResult.success(exchanges.refresh(company(),id,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:config") @GetMapping("/configs") public AjaxResult configs(){return AjaxResult.success(exchanges.configurations(company()));}
    @RequiresPermissions("order:exchange:config") @PostMapping("/configs") public AjaxResult save(@RequestBody Map<String,Object> body){exchanges.save(company(),body,SecurityUtils.getUsername());sync.kick(company());return AjaxResult.success();}
    @RequiresPermissions("order:exchange:sync") @PostMapping("/sync") public AjaxResult sync(@RequestBody Map<String,Object> body){Map<String,Object> r=sync.submit(company(),body,SecurityUtils.getUsername());sync.kick(company());return AjaxResult.success(r);}
    @RequiresPermissions("order:exchange:sync") @GetMapping("/tasks") public AjaxResult tasks(){return AjaxResult.success(sync.tasks(company()));}
    @RequiresPermissions("order:exchange:sync") @GetMapping("/tasks/{id}") public AjaxResult syncTask(@PathVariable long id){return AjaxResult.success(sync.task(company(),id));}
    @RequiresPermissions("order:exchange:sync") @PostMapping("/tasks/{id}/retry") public AjaxResult retrySync(@PathVariable long id){String c=company();sync.retry(c,id);sync.kick(c);return AjaxResult.success();}
    @InnerAuth @PostMapping("/internal/scan-all") public R<Map<String,Object>> scan(){return R.ok(sync.poll());}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
