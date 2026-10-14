package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="fulfillmentScanClient",name="oms-order",configuration=FulfillmentScheduleFeignConfig.class)
public interface FulfillmentScanClient {
    @PostMapping("/orders/internal/schedule/{kind}") R<Map<String,Object>> scan(@PathVariable("kind") String kind,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
