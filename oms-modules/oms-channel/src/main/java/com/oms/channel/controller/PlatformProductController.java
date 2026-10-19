package com.oms.channel.controller;
import com.oms.channel.platform.PlatformProductService;
import com.oms.channel.platform.StockSyncService;
import com.oms.common.tenant.TenantRouting;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/platformProducts") public class PlatformProductController {
 @Resource PlatformProductService service;
 @Resource StockSyncService stock;
 @Resource TenantRouting tenants;
 String company(){return TenantRouting.company(SecurityUtils.getLoginUser().getCompanyCode());}
 @RequiresPermissions(value={"channel:product:query","channel:stockSync:query"},logical=Logical.OR) @GetMapping("/options") public AjaxResult options(){return AjaxResult.success(stock.options(company()));}
 @RequiresPermissions("channel:product:query") @GetMapping("/items") public AjaxResult items(@RequestParam(required=false)Long channelId,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String state,@RequestParam(defaultValue="")String listing,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.items(company(),channelId,keyword,state,listing,pageNum,pageSize));}
 @RequiresPermissions("channel:product:query") @GetMapping("/items/{channel}/{item}/skus") public AjaxResult specs(@PathVariable long channel,@PathVariable String item,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="50")int pageSize){return AjaxResult.success(service.specs(company(),channel,item,pageNum,pageSize));}
 @RequiresPermissions("channel:product:query") @GetMapping("/summary") public AjaxResult summary(@RequestParam(required=false)Long channelId){return AjaxResult.success(service.summary(company(),channelId));}
 @RequiresPermissions("channel:product:query") @GetMapping public AjaxResult list(@RequestParam(required=false)Long channelId,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String state,@RequestParam(defaultValue="")String listing,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.list(company(),channelId,keyword,state,listing,pageNum,pageSize));}
 @RequiresPermissions("channel:product:query") @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(company(),id));}
 @RequiresPermissions("channel:product:bind") @GetMapping("/{id}/suggestions") public AjaxResult suggestions(@PathVariable long id){return AjaxResult.success(service.suggestions(company(),id));}
 @RequiresPermissions("channel:product:bind") @PostMapping("/goods/search") public AjaxResult search(@RequestBody Map<String,Object> body){return AjaxResult.success(service.search(company(),body));}
 @RequiresPermissions("channel:product:bind") @PostMapping("/{id}/binding") public AjaxResult bind(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.bind(company(),id,body,SecurityUtils.getUsername()));}
 @RequiresPermissions("channel:product:pull") @PostMapping("/pull/{channel}") public AjaxResult pull(@PathVariable long channel,@RequestBody Map<String,Object> body){return AjaxResult.success(service.pull(company(),channel,body,SecurityUtils.getUsername()));}
 @RequiresPermissions("channel:product:query") @GetMapping("/pull/{channel}/tasks") public AjaxResult tasks(@PathVariable long channel){return AjaxResult.success(service.tasks(company(),channel));}
 @RequiresPermissions("channel:product:query") @GetMapping("/tasks/{id}") public AjaxResult task(@PathVariable long id){return AjaxResult.success(service.task(company(),id));}
 @RequiresPermissions("channel:product:pull") @PostMapping("/tasks/{id}/retry") public AjaxResult retry(@PathVariable long id){service.retry(company(),id);return AjaxResult.success(service.task(company(),id));}
 @RequiresPermissions(value={"channel:product:query","channel:stockSync:query"},logical=Logical.OR) @GetMapping("/bound") public AjaxResult bound(@RequestParam long channelId,@RequestParam String skuSn){return AjaxResult.success(service.boundOptions(company(),channelId,skuSn));}
 @InnerAuth @PostMapping("/internal/scan-all") public R<Map<String,Object>> scan(){Map<String,Object> result=new LinkedHashMap<>();for(String c:tenants.companies())try(TenantRouting.Binding ignored=tenants.open(c)){result.put(c,service.scan(c));}return R.ok(result);}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
