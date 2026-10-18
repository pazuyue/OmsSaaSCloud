package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="refundSyncLog",name="oms-channel",configuration=OrderShipmentWmsFeignConfig.class)
public interface RefundSyncLog {
    @PostMapping("/platform/log-center/internal/refund-sync/start") R<Map<String,Object>> start(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/platform/log-center/internal/refund-sync/finish") R<Boolean> finish(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
