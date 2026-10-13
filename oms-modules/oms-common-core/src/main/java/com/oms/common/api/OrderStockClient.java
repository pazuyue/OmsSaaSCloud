package com.oms.common.api;

import com.oms.common.model.dto.OrderStockCommand;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@FeignClient(contextId="orderStockClient",name="oms-inventory")
public interface OrderStockClient {
    @PostMapping("/orderStock/internal/diagnostics")
    R<List<Map<String,Object>>> diagnostics(@RequestParam("company_code") String company,@RequestBody List<Map<String,Object>> keys,
        @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/orderStock/internal/demands")
    R<List<Map<String,Object>>> demands(@RequestParam("company_code") String company,@RequestParam("channel_id") long channel,
        @RequestParam("platform_tid") String tid,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/{action}")
    R<List<Map<String,Object>>> execute(@PathVariable("action") String action,@RequestParam("company_code") String company,
        @RequestBody OrderStockCommand command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
