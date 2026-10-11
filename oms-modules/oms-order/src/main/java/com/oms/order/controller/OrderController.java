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
import static com.oms.order.service.OrderValues.*;

@RestController @RequestMapping("/orders")
public class OrderController {
    @Resource private OrderVoidService voids;
    @RequiresPermissions("order:order:void") @PostMapping("/business/void-batch") public AjaxResult voidBatch(@RequestBody Map<String,Object> body){List<Map<String,Object>> result=voids.request(company(),body,SecurityUtils.getUsername());coordinator.poll();return AjaxResult.success(result);}
    @RequiresPermissions("order:order:list") @PostMapping("/business/void-results") public AjaxResult voidResults(@RequestBody List<Long> ids){return AjaxResult.success(businessOrders.results(company(),ids));}

    @Resource private OrderReviewService reviews;
    @RequiresPermissions("order:order:review") @PostMapping("/business/approve-batch") public AjaxResult approve(@RequestBody Map<String,Object> body){return AjaxResult.success(reviews.apply(company(),"approve",body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:order:hold") @PostMapping("/business/hold-batch") public AjaxResult hold(@RequestBody Map<String,Object> body){return AjaxResult.success(reviews.apply(company(),"hold",body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:order:hold") @PostMapping("/business/resume-batch") public AjaxResult resume(@RequestBody Map<String,Object> body){List<Map<String,Object>> result=reviews.apply(company(),"resume",body,SecurityUtils.getUsername());coordinator.poll();return AjaxResult.success(result);}

    @Resource private OrderWorkspaceService workspace;
    @Resource private OrderCoordinator coordinator;
    @Resource private OrderRepository repository;
    @Resource private OrderProductMatchService productMatch;
    @Resource private OrderBusinessService businessOrders;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    @InnerAuth @PostMapping("/internal/scan-all") public R<Map<String,Object>> scan(){return R.ok(coordinator.poll());}
    @RequiresPermissions("order:order:list") @GetMapping("/catalog") public AjaxResult catalog(){return AjaxResult.success(workspace.catalog(company()));}
    @RequiresPermissions("order:pull:config") @GetMapping("/configs") public AjaxResult configs(){return AjaxResult.success(workspace.configs(company()));}
    @RequiresPermissions("order:pull:config") @PostMapping("/configs") public AjaxResult save(@RequestBody Map<String,Object> body){workspace.save(company(),body,SecurityUtils.getUsername());return AjaxResult.success();}
    @RequiresPermissions("order:pull:config") @PostMapping("/configs/verify-source") public AjaxResult verifySource(@RequestBody Map<String,Object> body){return AjaxResult.success(workspace.verifySource(company(),body));}
    @RequiresPermissions("order:pull:execute") @PostMapping("/backfill") public AjaxResult backfill(@RequestBody Map<String,Object> body){List<Long> ids=new ArrayList<>();Object channels=body.get("channel_ids");require(channels instanceof List,"请选择店铺");for(Object id:(List<?>)channels)ids.add(Long.valueOf(id.toString()));List<Long> tasks=workspace.backfill(company(),ids,text(body,"start_time"),text(body,"end_time"),SecurityUtils.getUsername());coordinator.poll();return AjaxResult.success(tasks);}
    @RequiresPermissions("order:order:retry") @PostMapping("/platform/{id}/retry") public AjaxResult retry(@PathVariable long id){workspace.retry(company(),id,SecurityUtils.getUsername());coordinator.poll();return AjaxResult.success();}
    @RequiresPermissions("order:order:retry") @PostMapping("/platform/{id}/match-product") public AjaxResult matchProduct(@PathVariable long id,@RequestBody Map<String,Object> body){productMatch.match(company(),id,body,SecurityUtils.getUsername());coordinator.poll();return AjaxResult.success();}
    @RequiresPermissions("order:order:retry") @PostMapping("/platform/retry-batch") public AjaxResult retryBatch(@RequestBody List<Long> ids){List<Map<String,Object>> result=workspace.retryBatch(company(),ids,SecurityUtils.getUsername());coordinator.poll();return AjaxResult.success(result);}
    @RequiresPermissions("order:order:list") @PostMapping("/platform/retry-results") public AjaxResult retryResults(@RequestBody List<Long> ids){return AjaxResult.success(workspace.retryResults(company(),ids));}
    @RequiresPermissions("order:order:retry") @PostMapping("/business/reserve-batch") public AjaxResult reserveBatch(@RequestBody List<Long> ids){List<Map<String,Object>> result=businessOrders.retry(company(),ids,SecurityUtils.getUsername());coordinator.poll();return AjaxResult.success(result);}
    @RequiresPermissions("order:order:list") @PostMapping("/business/reserve-results") public AjaxResult reserveResults(@RequestBody List<Long> ids){return AjaxResult.success(businessOrders.results(company(),ids));}
    @RequiresPermissions("order:pull:execute") @PostMapping("/tasks/{id}/retry") public AjaxResult retryTask(@PathVariable long id){int changed=repository.db().update("UPDATE oms_order_pull_task SET status='PENDING',attempts=0,next_retry_at=NOW(),error_message='' WHERE company_code=? AND id=? AND status='FAILED'",company(),id);require(changed==1,"任务不存在或当前无需重试");coordinator.poll();return AjaxResult.success();}
    @RequiresPermissions("order:order:list") @GetMapping("/{view}/list") public TableDataInfo list(@PathVariable String view,@RequestParam(required=false) Long channelId,@RequestParam(defaultValue="") String tid,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String sku,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="0") int waitingMinutes,@RequestParam(defaultValue="") String reviewStatus,@RequestParam(defaultValue="") String processingStatus,@RequestParam(defaultValue="") String documentStatus){if("business".equals(view))return businessOrders.list(company(),channelId,tid,status,sku,from,to,waitingMinutes,pageNum,pageSize,reviewStatus,processingStatus,documentStatus);return workspace.list(company(),view,channelId,tid,status,pageNum,pageSize,category,sku,from,to,waitingMinutes);}
    @RequiresPermissions("order:order:query") @GetMapping("/{view}/{id}") public AjaxResult detail(@PathVariable String view,@PathVariable long id){require(Arrays.asList("platform","business").contains(view),"详情类型无效");return AjaxResult.success("business".equals(view)?businessOrders.detail(company(),id):workspace.detail(company(),id,false));}
    @RequiresPermissions("order:order:raw") @GetMapping("/platform/{id}/payload") public AjaxResult payload(@PathVariable long id){Map<String,Object> order=repository.raw(company(),id);Map<String,Object> result=new LinkedHashMap<>();result.put("latest",order.get("raw_payload"));result.put("processing",order.get("processing_payload"));return AjaxResult.success(result);}
}
