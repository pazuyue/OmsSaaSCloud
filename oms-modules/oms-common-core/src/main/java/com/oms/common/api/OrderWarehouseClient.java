package com.oms.common.api;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="orderWarehouseClient",name="oms-supplychain")
public interface OrderWarehouseClient {
    @GetMapping("/orderCatalog/internal/stores")
    R<List<Map<String,Object>>> stores(@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
