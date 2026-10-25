package com.oms.order.controller;
import com.oms.order.service.SharedReturnAuthorization;
import com.oms.common.tenant.TenantRouting;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/afterSaleReturns/internal") public class SharedReturnAuthorizationController {
    @Resource SharedReturnAuthorization authorization;
    @Resource TenantRouting tenants;
    @InnerAuth @PostMapping("/authorization") public R<Map<String,Object>> context(@RequestParam("company_code") String c,@RequestBody List<Map<String,Object>> members){String company=TenantRouting.company(c);return R.ok(tenants.callForContinuation(company,()->authorization.context(company,members)));}
}
