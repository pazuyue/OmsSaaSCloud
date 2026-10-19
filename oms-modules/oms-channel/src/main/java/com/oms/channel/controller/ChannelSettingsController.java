package com.oms.channel.controller;
import com.oms.channel.platform.*;
import com.oms.common.tenant.TenantRouting;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/channelSettings") public class ChannelSettingsController {
 @Resource ChannelSettings settings;@Resource StockSyncService stock;@Resource PlatformInteractionLog logs;
 String company(){return TenantRouting.company(SecurityUtils.getLoginUser().getCompanyCode());}
 @GetMapping("/options") @RequiresPermissions("channel:platform:query") public AjaxResult options(){return AjaxResult.success(settings.options(company()));}
 @GetMapping("/products/{id}") @RequiresPermissions(value={"channel:platform:query","channel:product:query"},logical=Logical.OR) public AjaxResult products(@PathVariable long id){return AjaxResult.success(settings.products(company(),id));}
 @PostMapping("/products/{id}") @RequiresPermissions("channel:platform:config") public AjaxResult products(@PathVariable long id,@RequestBody Map<String,Object> body){Map<String,Object> r=settings.saveProducts(company(),id,body,SecurityUtils.getUsername());audit(id,"PRODUCT_POLICY",body);return AjaxResult.success(r);}
 @PostMapping("/switches/{id}") @RequiresPermissions("channel:platform:config") public AjaxResult switches(@PathVariable long id,@RequestBody Map<String,Object> body){Map<String,Object> r=settings.switches(company(),id,body);audit(id,"CHANNEL_SWITCHES",body);return AjaxResult.success(r);}
 @GetMapping("/retention") @RequiresPermissions("channel:platform:query") public AjaxResult retention(){return AjaxResult.success(settings.retention(company()));}
 @PostMapping("/retention") @RequiresPermissions("channel:platform:config") public AjaxResult retention(@RequestBody Map<String,Object> body){Map<String,Object> r=settings.saveRetention(company(),body,SecurityUtils.getUsername());audit(null,"LOG_RETENTION",body);return AjaxResult.success(r);}
 void audit(Long id,String action,Object body){Map<String,Object> log=logs.begin(company(),id,null,action,"CONFIG","LOCAL","CONFIG",SecurityUtils.getUsername(),Collections.singletonMap("trigger_source","USER"),body);logs.finish(log,"SUCCESS","",Collections.emptyMap(),0,null,false);}
 @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
