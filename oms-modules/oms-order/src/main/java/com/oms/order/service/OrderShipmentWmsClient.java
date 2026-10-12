package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@FeignClient(contextId="orderShipmentWmsClient",name="oms-supplychain",configuration=OrderShipmentWmsFeignConfig.class)
public interface OrderShipmentWmsClient {
    @PostMapping("/shipmentWms/internal/resolve") R<Map<String,Object>> resolve(@RequestParam("company_code") String company,@RequestBody Map<String,Object> payload,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/submit") R<Map<String,Object>> submit(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/query") R<Map<String,Object>> query(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
