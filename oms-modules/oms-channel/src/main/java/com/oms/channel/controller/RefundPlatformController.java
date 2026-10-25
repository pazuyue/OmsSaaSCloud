package com.oms.channel.controller;
import com.oms.channel.platform.*;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/refundPlatform") public class RefundPlatformController {
 @Resource RefundPlatformService service;
 @Resource ChannelPlatformService authorization;
 @InnerAuth @GetMapping("/internal/capabilities") public R<List<Map<String,Object>>> capabilities(@RequestParam("company_code") String c){return R.ok(service.capabilities(c));}
 @InnerAuth @GetMapping("/internal/{channel}/rate-keys") public R<List<String>> rateKeys(@RequestParam("company_code") String c,@PathVariable long channel){return R.ok(service.rateKeys(c,channel));}
 @InnerAuth @GetMapping("/internal/{channel}/capability") public R<Map<String,Object>> capability(@RequestParam("company_code") String c,@PathVariable long channel){return R.ok(service.capability(c,channel));}
 @InnerAuth @PostMapping("/internal/{channel}/call") public R<Map<String,Object>> call(@RequestParam("company_code") String c,@PathVariable long channel,@RequestBody Map<String,Object> body){return R.ok(service.call(c,channel,body));}
 @RequiresPermissions("order:refund:config") @GetMapping("/{channel}/capability") public AjaxResult settings(@PathVariable long channel){return AjaxResult.success(service.capability(SecurityUtils.getLoginUser().getCompanyCode(),channel));}
 @RequiresPermissions("order:refund:config") @PostMapping("/{channel}/access") public AjaxResult access(@PathVariable long channel,@RequestBody Map<String,Object> body){service.access(SecurityUtils.getLoginUser().getCompanyCode(),channel,body,SecurityUtils.getUsername());return AjaxResult.success();}
 @RequiresPermissions("channel:platform:authorize") @PostMapping("/{channel}/payment-authorize") public AjaxResult authorize(@PathVariable long channel){return AjaxResult.success(ChannelPlatformService.map("url",authorization.authorizeRefundPayment(SecurityUtils.getLoginUser().getCompanyCode(),channel,SecurityUtils.getUserId(),SecurityUtils.getUsername())));}
}
