package com.oms.order.controller;
import com.oms.order.service.*;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/business-settings") public class OrderBusinessSettingsController {
    @Resource OrderBusinessConfiguration configuration;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    private Set<String> permissions(){Set<String> p=SecurityUtils.getLoginUser().getPermissions();return p==null?Collections.emptySet():p;}
    @RequiresPermissions(value={"order:pull:config","order:refund:config","order:exchange:config","order:source:config","order:shipment:platformConfig","order:gift:list","channel:platform:query"},logical=Logical.OR)
    @GetMapping public AjaxResult list(@RequestParam Map<String,String> query){return AjaxResult.success(configuration.listing(company(),permissions(),query));}
    @RequiresPermissions(value={"order:pull:config","order:refund:config","order:exchange:config","order:source:config","order:shipment:platformConfig","order:gift:list","channel:platform:query"},logical=Logical.OR)
    @GetMapping("/shops/{channel}") public AjaxResult shop(@PathVariable long channel){return AjaxResult.success(configuration.detail(company(),permissions(),channel));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
}
