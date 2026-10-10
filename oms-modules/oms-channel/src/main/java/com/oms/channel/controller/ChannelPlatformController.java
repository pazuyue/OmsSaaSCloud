package com.oms.channel.controller;

import com.oms.channel.platform.ChannelPlatformService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController
@RequestMapping("/platform")
public class ChannelPlatformController extends BaseController {
    @Resource private ChannelPlatformService service;
    private String company() {String c=SecurityUtils.getLoginUser().getCompanyCode();if(c==null||c.isEmpty())throw new IllegalArgumentException("请先选择登录公司");return c;}
    @GetMapping("/apps") @RequiresPermissions("channel:platform:query")
    public AjaxResult apps(){return success(service.apps(company()));}
    @PostMapping("/apps") @RequiresPermissions("channel:platform:config")
    public AjaxResult saveApp(@RequestBody Map<String,Object> body){return success(service.saveApp(company(),body,SecurityUtils.getUsername()));}
    @GetMapping("/shops/{id}") @RequiresPermissions("channel:platform:query")
    public AjaxResult detail(@PathVariable long id){return success(service.detail(company(),id));}
    @GetMapping("/summaries") @RequiresPermissions("channel:channel:list")
    public AjaxResult summaries(@RequestParam List<Long> ids){return success(service.summaries(company(),ids));}
    @PostMapping("/shops/{id}/binding") @RequiresPermissions("channel:platform:config")
    public AjaxResult bind(@PathVariable long id,@RequestBody Map<String,Object> body){service.bind(company(),id,body,SecurityUtils.getUsername());return success();}
    @PostMapping("/shops/{id}/authorize") @RequiresPermissions("channel:platform:authorize")
    public AjaxResult authorize(@PathVariable long id){return success(ChannelPlatformService.map("url",service.authorize(company(),id,SecurityUtils.getUserId(),SecurityUtils.getUsername())));}
    @PostMapping("/shops/{id}/simulate") @RequiresPermissions("channel:platform:authorize")
    public AjaxResult simulate(@PathVariable long id){service.simulate(company(),id,SecurityUtils.getUsername());return success();}
    @PostMapping("/oauth/complete") @RequiresPermissions("channel:platform:authorize")
    public AjaxResult complete(@RequestBody Map<String,String> body){return success(service.complete(company(),SecurityUtils.getUserId(),body.get("state"),body.get("code"),SecurityUtils.getUsername()));}
    @PostMapping("/shops/{id}/refresh") @RequiresPermissions("channel:platform:authorize")
    public AjaxResult refresh(@PathVariable long id){service.refresh(company(),id,SecurityUtils.getUsername());return success();}
    @PostMapping("/shops/{id}/disable") @RequiresPermissions("channel:platform:authorize")
    public AjaxResult disable(@PathVariable long id){service.disable(company(),id,SecurityUtils.getUsername());return success();}
    @PostMapping("/shops/{id}/query/{kind}") @RequiresPermissions("channel:platform:query")
    public AjaxResult query(@PathVariable long id,@PathVariable String kind){service.query(company(),id,kind,SecurityUtils.getUsername());return success();}
    @PostMapping("/shops/{id}/reminder") @RequiresPermissions("channel:platform:config")
    public AjaxResult reminder(@PathVariable long id,@RequestBody Map<String,Object> body){service.reminder(company(),id,Integer.parseInt(body.get("days").toString()),Boolean.TRUE.equals(body.get("enabled")),SecurityUtils.getUsername());return success();}
    @GetMapping("/reminders") @RequiresPermissions("channel:platform:query")
    public AjaxResult reminders(){return success(service.reminders(company()));}
    @GetMapping("/logs") @RequiresPermissions("channel:platform:log")
    public AjaxResult logs(@RequestParam(required=false) Long channelId,@RequestParam(required=false) String result,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return success(service.logs(company(),channelId,result,pageNum,pageSize));}
    @ExceptionHandler({IllegalArgumentException.class,com.oms.channel.platform.TmallClient.Failure.class})
    public AjaxResult validation(RuntimeException e){return error(e.getMessage());}
}
