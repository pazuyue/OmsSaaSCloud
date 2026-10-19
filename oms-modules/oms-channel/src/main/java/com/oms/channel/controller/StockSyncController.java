package com.oms.channel.controller;
import com.oms.channel.platform.StockSyncService;
import com.oms.common.tenant.TenantRouting;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/stockSync") public class StockSyncController {
 @Resource StockSyncService service;
 @Resource TenantRouting tenants;
 String company(){return TenantRouting.company(SecurityUtils.getLoginUser().getCompanyCode());}
 @RequiresPermissions("channel:stockSync:query") @GetMapping("/options") public AjaxResult options(){return AjaxResult.success(service.options(company()));}
 @RequiresPermissions("channel:stockSync:query") @GetMapping public AjaxResult list(@RequestParam(required=false)Long channelId,@RequestParam(required=false)String skuSn,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.list(company(),channelId,skuSn,pageNum,pageSize));}
 @RequiresPermissions("channel:stockSync:query") @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(company(),id));}
 @RequiresPermissions("channel:stockSync:query") @GetMapping("/{id}/runs/{run}") public AjaxResult run(@PathVariable long id,@PathVariable long run){return AjaxResult.success(service.runDetail(company(),id,run));}
 @RequiresPermissions("channel:stockSync:edit") @PostMapping public AjaxResult save(@RequestBody Map<String,Object> body){return AjaxResult.success(service.save(company(),body,SecurityUtils.getUsername()));}
 @RequiresPermissions("channel:stockSync:query") @PostMapping("/{id}/preview") public AjaxResult preview(@PathVariable long id){return AjaxResult.success(service.preview(company(),id));}
 @RequiresPermissions("channel:stockSync:execute") @PostMapping("/{id}/run") public AjaxResult enqueue(@PathVariable long id,@RequestBody Map<String,Object> body){service.enqueue(company(),id,Long.parseLong(String.valueOf(body.get("revision"))),SecurityUtils.getUsername());return AjaxResult.success(service.detail(company(),id));}
 @RequiresPermissions("channel:stockSync:execute") @PostMapping("/{id}/links/{link}/action") public AjaxResult action(@PathVariable long id,@PathVariable long link,@RequestBody Map<String,Object> body){return AjaxResult.success(service.linkAction(company(),id,link,body,SecurityUtils.getUsername()));}
 @InnerAuth @PostMapping("/internal/scan-all") public R<Map<String,Object>> scan(){Map<String,Object> result=new LinkedHashMap<>();for(String c:tenants.companies())try(TenantRouting.Binding ignored=tenants.open(c)){result.put(c,service.scan(c));}return R.ok(result);}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
 @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public AjaxResult duplicate(){return AjaxResult.error("该店铺SKU已存在同步组，或平台SKU已绑定其他同步来源");}
}
