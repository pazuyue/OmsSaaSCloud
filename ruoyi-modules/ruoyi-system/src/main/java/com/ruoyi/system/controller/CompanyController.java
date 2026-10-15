package com.ruoyi.system.controller;

import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.company.CompanyOnboardingService;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
import static com.ruoyi.system.company.CompanyServices.*;

/** Platform onboarding. Company identity is never taken from an ordinary user's request. */
@RestController
@RequestMapping("/company")
public class CompanyController {
    @Resource private CompanyOnboardingService companies;
    private void platform(){require(SecurityUtils.isAdmin(SecurityUtils.getUserId()),"仅平台管理员可维护公司接入配置");}
    private String own(){require(SecurityUtils.getLoginUser()!=null,"请先登录");return company(SecurityUtils.getLoginUser().getCompanyCode());}
    @EventListener(ApplicationReadyEvent.class)
    public void ready(){try{companies.warmStates();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("公司执行开关预热未完成；可在公司管理中同步");}}
    @GetMapping("/options") public AjaxResult options(@RequestParam(defaultValue="") String query){return AjaxResult.success(companies.options(query,own(),SecurityUtils.isAdmin(SecurityUtils.getUserId())));}
    @GetMapping("/mine") public AjaxResult mine(){return AjaxResult.success(companies.mine(own()));}
    @RequiresPermissions("system:company:list") @GetMapping("/list")
    public AjaxResult list(@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize){platform();AjaxResult result=AjaxResult.success();result.putAll(companies.list(query,status,pageNum,pageSize));return result;}
    @RequiresPermissions("system:company:query") @GetMapping("/detail/{code}") public AjaxResult detail(@PathVariable String code){platform();return AjaxResult.success(companies.detail(code));}
    @RequiresPermissions("system:company:check") @PostMapping("/catalog") public AjaxResult catalog(){platform();return AjaxResult.success(companies.catalog());}
    @RequiresPermissions("system:company:add") @PostMapping public AjaxResult add(@RequestBody Map<String,Object> input){platform();require(number(input,"revision")==0,"新增公司不应包含已有版本");return AjaxResult.success(companies.detail(companies.save(input,SecurityUtils.getUsername())));}
    @RequiresPermissions("system:company:edit") @PutMapping public AjaxResult edit(@RequestBody Map<String,Object> input){platform();require(number(input,"revision")>0,"修改公司必须提供版本");return AjaxResult.success(companies.detail(companies.save(input,SecurityUtils.getUsername())));}
    @RequiresPermissions("system:company:check") @PostMapping("/{code}/check") public AjaxResult check(@PathVariable String code,@RequestBody Map<String,Object> input){platform();return AjaxResult.success(companies.check(code,number(input,"revision"),false,SecurityUtils.getUsername()));}
    @RequiresPermissions("system:company:check") @PostMapping("/{code}/detect") public AjaxResult detect(@PathVariable String code,@RequestBody Map<String,Object> input){platform();return AjaxResult.success(companies.check(code,number(input,"revision"),true,SecurityUtils.getUsername()));}
    @RequiresPermissions("system:company:publish") @PostMapping("/{code}/publish") public AjaxResult publish(@PathVariable String code,@RequestBody Map<String,Object> input){platform();return AjaxResult.success(companies.publish(code,number(input,"revision"),text(input,"request_id"),SecurityUtils.getUsername()));}
    @RequiresPermissions("system:company:enable") @PostMapping("/{code}/enable") public AjaxResult enable(@PathVariable String code,@RequestBody Map<String,Object> input){platform();return AjaxResult.success(companies.status(code,number(input,"revision"),"ENABLED",SecurityUtils.getUsername()));}
    @RequiresPermissions("system:company:disable") @PostMapping("/{code}/disable") public AjaxResult disable(@PathVariable String code,@RequestBody Map<String,Object> input){platform();return AjaxResult.success(companies.status(code,number(input,"revision"),"DISABLED",SecurityUtils.getUsername()));}
    @RequiresPermissions("system:company:edit") @PostMapping("/{code}/sync-state") public AjaxResult sync(@PathVariable String code,@RequestBody Map<String,Object> input){platform();return AjaxResult.success(companies.syncState(code,number(input,"revision"),SecurityUtils.getUsername()));}
    @RequiresPermissions("system:company:recover") @PostMapping("/{code}/releases/{id}/recover") public AjaxResult recover(@PathVariable String code,@PathVariable String id){platform();return AjaxResult.success(companies.recover(code,id,SecurityUtils.getUsername()));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
    @ExceptionHandler(RuntimeException.class) public AjaxResult unavailable(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("公司管理操作未完成: {}",e.getClass().getSimpleName());return AjaxResult.error("公司管理操作暂未完成，请刷新后查看记录或重试");}
}
