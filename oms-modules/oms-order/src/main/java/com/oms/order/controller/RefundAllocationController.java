package com.oms.order.controller;
import com.oms.common.tenant.TenantRouting;
import com.oms.order.service.RefundAllocationContext;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orders/internal") public class RefundAllocationController {
    @Resource RefundAllocationContext allocation;
    @Resource TenantRouting tenants;
    @InnerAuth @PostMapping("/refund-allocation-context") public R<Map<String,Object>> context(@RequestParam("company_code") String company,@RequestParam long channel,@RequestBody List<Long> plans){String c=TenantRouting.company(company);return R.ok(tenants.callForContinuation(c,()->allocation.context(c,channel,plans)));}
}
