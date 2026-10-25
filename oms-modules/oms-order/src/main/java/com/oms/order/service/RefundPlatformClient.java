package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="refundPlatformClient",name="oms-channel",configuration=OrderShipmentWmsFeignConfig.class)
public interface RefundPlatformClient {
 @GetMapping("/refundPlatform/internal/capabilities") R<List<Map<String,Object>>> capabilities(@RequestParam("company_code") String c,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
 @GetMapping("/refundPlatform/internal/{channel}/rate-keys") R<List<String>> rateKeys(@RequestParam("company_code") String c,@PathVariable("channel") long channel,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
 @GetMapping("/refundPlatform/internal/{channel}/capability") R<Map<String,Object>> capability(@RequestParam("company_code") String c,@PathVariable("channel") long channel,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
 @PostMapping("/refundPlatform/internal/{channel}/call") R<Map<String,Object>> call(@RequestParam("company_code") String c,@PathVariable("channel") long channel,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
