package com.oms.channel.controller;

import com.oms.channel.platform.ExchangePlatformService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/exchangePlatform")
public class ExchangePlatformController {
    @Resource private ExchangePlatformService service;
    @InnerAuth @GetMapping("/internal/{channel}/capability") public R<Map<String,Object>> capability(@RequestParam("company_code") String company,@PathVariable long channel){return R.ok(service.capability(company,channel));}
    @InnerAuth @PostMapping("/internal/{channel}/call") public R<Map<String,Object>> call(@RequestParam("company_code") String company,@PathVariable long channel,@RequestBody Map<String,Object> body){try{return R.ok(service.call(company,channel,body));}catch(com.oms.channel.platform.TmallClient.Failure e){Map<String,Object> failure=new LinkedHashMap<>();failure.put("outcome",e.unknown?"UNKNOWN":"FAILED");failure.put("error_code",e.code);return R.ok(failure);}}
    @RequiresPermissions("channel:platform:config") @PostMapping("/{channel}/access") public AjaxResult access(@PathVariable long channel,@RequestBody Map<String,Object> body){service.access(SecurityUtils.getLoginUser().getCompanyCode(),channel,body,SecurityUtils.getUsername());return AjaxResult.success();}
}
