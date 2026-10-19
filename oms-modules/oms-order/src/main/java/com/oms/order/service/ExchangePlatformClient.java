package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="exchangePlatformClient",name="oms-channel",configuration=OrderShipmentWmsFeignConfig.class)
public interface ExchangePlatformClient {
    @GetMapping("/exchangePlatform/internal/{channel}/rate-keys") R<List<String>> rateKeys(@RequestParam("company_code") String company,@PathVariable("channel") long channel,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/exchangePlatform/internal/{channel}/capability") R<Map<String,Object>> capability(@RequestParam("company_code") String company,@PathVariable("channel") long channel,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/exchangePlatform/internal/{channel}/call") R<Map<String,Object>> call(@RequestParam("company_code") String company,@PathVariable("channel") long channel,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
