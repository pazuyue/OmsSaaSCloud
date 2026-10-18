package com.oms.channel.controller;
import com.oms.channel.platform.PlatformInteractionLog;
import com.oms.common.tenant.TenantRouting;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/platform/log-maintenance/internal") public class PlatformLogMaintenanceController {
    @Resource PlatformInteractionLog logs;
    @Resource TenantRouting tenants;
    @InnerAuth @PostMapping("/purge-all") public R<Map<String,Object>> purge(){Map<String,Object> result=new LinkedHashMap<>();for(String company:tenants.companies())try(TenantRouting.Binding ignored=tenants.open(company)){result.put(company,logs.purge(company));}return R.ok(result);}
}
