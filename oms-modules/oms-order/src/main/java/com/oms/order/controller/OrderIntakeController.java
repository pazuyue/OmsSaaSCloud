package com.oms.order.controller;
import com.oms.order.service.*;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/intake") public class OrderIntakeController {
    @Resource OrderIntakeWorkspace workspace;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    private boolean can(String p){Set<String> permissions=SecurityUtils.getLoginUser().getPermissions();return permissions!=null&&(permissions.contains("*:*:*")||permissions.contains(p));}
    private Set<String> types(boolean config){Set<String> result=new LinkedHashSet<>();for(String t:Arrays.asList("ORDER","REFUND","EXCHANGE")){String p=t.equals("ORDER")?(config?"order:pull:config":"order:order:list"):"order:"+t.toLowerCase(Locale.ROOT)+":"+(config?"config":"sync");if(can(p))result.add(t);}return result;}
    @RequiresPermissions(value={"order:pull:execute","order:pull:config","order:source:config","order:order:list","order:refund:config","order:refund:sync","order:exchange:config","order:exchange:sync"},logical=Logical.OR)
    @GetMapping("/overview") public AjaxResult overview(){Map<String,Object> result=workspace.overview(company(),types(true));result.put("config_types",types(true));result.put("task_types",types(false));List<String> sync=new ArrayList<>();if(can("order:pull:execute"))sync.add("ORDER");if(can("order:refund:sync"))sync.add("REFUND");if(can("order:exchange:sync"))sync.add("EXCHANGE");result.put("sync_types",sync);return AjaxResult.success(result);}
    @RequiresPermissions(value={"order:order:list","order:refund:sync","order:exchange:sync"},logical=Logical.OR)
    @GetMapping("/tasks") public AjaxResult tasks(@RequestParam Map<String,String> input){return AjaxResult.success(workspace.tasks(company(),types(false),input));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
}
