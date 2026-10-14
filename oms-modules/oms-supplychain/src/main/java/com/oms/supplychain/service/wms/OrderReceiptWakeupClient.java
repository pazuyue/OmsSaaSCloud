package com.oms.supplychain.service.wms;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="orderReceiptWakeupClient",name="oms-order",configuration=OrderReceiptWakeupConfig.class)
public interface OrderReceiptWakeupClient {
    @PostMapping("/orders/internal/receipt-ready") R<Map<String,Object>> ready(@RequestParam("company_code") String company,@RequestParam("event_id") long event,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
