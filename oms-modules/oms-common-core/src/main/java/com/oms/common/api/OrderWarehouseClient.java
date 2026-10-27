package com.oms.common.api;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="orderWarehouseClient",name="oms-supplychain")
public interface OrderWarehouseClient {
    @PostMapping("/orderCatalog/internal/carrier-fact-retry")
    R<Map<String,Object>> carrierFactRetry(@RequestParam("company_code") String company,@RequestBody Map<String,Object> fact,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderCatalog/internal/carrier-directory")
    R<Map<String,Object>> carrierDirectory(@RequestParam("company_code") String company,@RequestBody Map<String,Object> scope,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/orderCatalog/internal/carrier-context")
    R<Map<String,Object>> carrierContext(@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/orderCatalog/internal/stores")
    R<List<Map<String,Object>>> stores(@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
