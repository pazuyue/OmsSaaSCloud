package com.oms.order.controller;
import com.oms.order.service.*;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

/** Separate admin endpoints backed by the tested source service. */
@RestController @RequestMapping("/orders/sources")
public class PushSourceController {
    @Resource private PushSourceService sources;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    @RequiresPermissions("order:source:config") @GetMapping public AjaxResult list(){return AjaxResult.success(sources.list(company()));}
    @RequiresPermissions("order:source:config") @PostMapping public AjaxResult save(@RequestBody Map<String,Object> body){return AjaxResult.success(sources.save(company(),body));}
    @RequiresPermissions("order:source:config") @PostMapping("/test") public AjaxResult test(@RequestBody Map<String,Object> body){sources.test(company(),body);return AjaxResult.success("连接和订单表检查通过");}
    @RequiresPermissions("order:source:config") @PostMapping("/{key}/test") public AjaxResult testSaved(@PathVariable String key){sources.testSaved(company(),key);return AjaxResult.success("连接和订单表检查通过");}
    @RequiresPermissions("order:source:config") @DeleteMapping("/{key}") public AjaxResult delete(@PathVariable String key){sources.delete(company(),key);return AjaxResult.success();}
}
