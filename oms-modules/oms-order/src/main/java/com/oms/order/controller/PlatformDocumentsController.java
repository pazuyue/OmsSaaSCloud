package com.oms.order.controller;

import com.oms.order.service.*;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/platform-documents")
public class PlatformDocumentsController {
    @Resource PlatformDocumentsWorkspace workspace;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    private Set<String> types(){Set<String> p=SecurityUtils.getLoginUser().getPermissions(),types=new LinkedHashSet<>();for(String kind:Arrays.asList("ORDER","REFUND","EXCHANGE")){String permission=kind.equals("ORDER")?"order:order:list":"order:"+kind.toLowerCase(Locale.ROOT)+":list";if(p!=null&&(p.contains("*:*:*")||p.contains(permission)))types.add(kind);}return types;}
    @RequiresPermissions(value={"order:order:list","order:refund:list","order:exchange:list"},logical=Logical.OR)
    @GetMapping("/options") public AjaxResult options(){return AjaxResult.success(workspace.options(company(),types()));}
    @RequiresPermissions(value={"order:order:list","order:refund:list","order:exchange:list"},logical=Logical.OR)
    @GetMapping("/list") public AjaxResult list(@RequestParam String kind,@RequestParam Map<String,String> q){return AjaxResult.success(workspace.list(company(),types(),kind,q));}
    @RequiresPermissions(value={"order:order:list","order:refund:list","order:exchange:list"},logical=Logical.OR)
    @GetMapping("/related") public AjaxResult related(@RequestParam Map<String,String> q){return AjaxResult.success(workspace.related(company(),types(),q));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
