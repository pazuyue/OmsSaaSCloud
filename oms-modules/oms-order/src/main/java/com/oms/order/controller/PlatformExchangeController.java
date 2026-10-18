package com.oms.order.controller;

import com.oms.order.service.*;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/exchanges/platform")
public class PlatformExchangeController {
    @Resource PlatformExchangeService service;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    @RequiresPermissions("order:exchange:list") @GetMapping public AjaxResult list(@RequestParam Map<String,String> query){return AjaxResult.success(service.list(company(),query));}
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(company(),id));}
    @RequiresPermissions("order:exchange:raw") @GetMapping("/{id}/snapshots/{snapshot}") public AjaxResult snapshot(@PathVariable long id,@PathVariable long snapshot){return AjaxResult.success(service.snapshot(company(),id,snapshot));}
    @RequiresPermissions("order:exchange:review") @PostMapping("/{id}/convert") public AjaxResult convert(@PathVariable long id){return AjaxResult.success(service.convert(company(),id,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:query") @GetMapping("/{id}/candidates") public AjaxResult candidates(@PathVariable long id){return AjaxResult.success(service.candidates(company(),id));}
    @RequiresPermissions("order:exchange:review") @PostMapping("/{id}/link") public AjaxResult link(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.link(company(),id,body,SecurityUtils.getUsername()));}
    @RequiresPermissions("order:exchange:sync") @PostMapping("/{id}/refresh") public AjaxResult refresh(@PathVariable long id){return AjaxResult.success(service.refresh(company(),id,SecurityUtils.getUsername()));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
}
