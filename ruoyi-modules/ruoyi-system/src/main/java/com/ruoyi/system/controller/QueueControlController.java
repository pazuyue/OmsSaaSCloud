package com.ruoyi.system.controller;
import com.ruoyi.system.messaging.QueueControlStore;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;import java.util.*;
@RestController @RequestMapping("/mqQueues") public class QueueControlController {
 @Resource QueueControlStore store;
 @Resource com.ruoyi.system.messaging.QueueDeadLetters deadLetters;
 @RequiresPermissions("oms:queue:query") @GetMapping("/dead-letters/{id}/reviews") public AjaxResult reviews(@PathVariable long id){platform();return AjaxResult.success(deadLetters.reviews(id));}
 @RequiresPermissions("oms:queue:query") @GetMapping("/{type}/dead-letters") public AjaxResult deadLetters(@PathVariable String type,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){platform();return AjaxResult.success(deadLetters.list(type,pageNum,pageSize));}
 @RequiresPermissions("oms:queue:edit") @PostMapping("/dead-letters/{id}/review") public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object> body){platform();deadLetters.review(id,QueueControlStore.s(body,"reason"),SecurityUtils.getUsername());return AjaxResult.success();}
 @InnerAuth @PostMapping("/internal/dead-report") public R<Boolean> deadReport(@RequestBody Map<String,Object> body){deadLetters.report(body);return R.ok(true);}
 @InnerAuth @PostMapping("/internal/dead-checks") public R<List<Map<String,Object>>> deadChecks(@RequestBody Map<String,Object> body){return R.ok(deadLetters.checks(QueueControlStore.s(body,"service")));}
 @Resource com.ruoyi.system.messaging.QueueMetricsDiscovery metricsDiscovery;
 @org.springframework.beans.factory.annotation.Value("${oms.messaging.monitor.grafana-url:}") String grafana;
 @org.springframework.beans.factory.annotation.Value("${oms.messaging.monitor.prometheus-url:}") String prometheus;
 void platform(){if(!SecurityUtils.isAdmin(SecurityUtils.getUserId()))throw new IllegalArgumentException("仅平台管理员可管理共享消息队列");}
 @RequiresPermissions("oms:queue:query") @GetMapping public AjaxResult list(){platform();Map<String,Object> r=new LinkedHashMap<>();r.put("queues",store.overview());r.put("grafana",grafana);r.put("prometheus",prometheus);return AjaxResult.success(r);}
 @RequiresPermissions("oms:queue:query") @GetMapping("/{type}") public AjaxResult detail(@PathVariable String type){platform();return AjaxResult.success(store.detail(type,true));}
 @RequiresPermissions("oms:queue:edit") @PutMapping("/{type}") public AjaxResult save(@PathVariable String type,@RequestBody Map<String,Object> body){platform();return AjaxResult.success(store.save(type,body,SecurityUtils.getUsername()));}
 @InnerAuth @GetMapping("/internal/prometheus-targets") public org.springframework.http.ResponseEntity<List<Map<String,Object>>> metricsTargets(){try{return org.springframework.http.ResponseEntity.ok(metricsDiscovery.targets());}catch(IllegalStateException e){return org.springframework.http.ResponseEntity.status(503).build();}}
 @InnerAuth @PostMapping("/internal/snapshot") public R<List<Map<String,Object>>> snapshot(@RequestBody Map<String,Object> body){return R.ok(store.snapshot(QueueControlStore.s(body,"service"),QueueControlStore.s(body,"instance")));}
 @InnerAuth @PostMapping("/internal/heartbeat") public R<Boolean> heartbeat(@RequestBody Map<String,Object> body){store.heartbeat(body);return R.ok(true);}
 @SuppressWarnings("unchecked") @InnerAuth @PostMapping("/internal/rate") public R<Boolean> rate(@RequestBody Map<String,Object> body){return R.ok(store.rate(QueueControlStore.s(body,"service"),QueueControlStore.s(body,"instance"),QueueControlStore.s(body,"business_type"),(List<String>)body.get("keys")));}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
