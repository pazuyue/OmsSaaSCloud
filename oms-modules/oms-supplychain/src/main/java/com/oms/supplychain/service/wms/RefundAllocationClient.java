package com.oms.supplychain.service.wms;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="refundAllocationClient",name="oms-order",configuration=OrderReceiptWakeupConfig.class)
public interface RefundAllocationClient {
    @PostMapping("/orders/internal/refund-allocation-context") R<Map<String,Object>> context(@RequestParam("company_code") String company,@RequestParam("channel") long channel,@RequestBody List<Long> plans,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
