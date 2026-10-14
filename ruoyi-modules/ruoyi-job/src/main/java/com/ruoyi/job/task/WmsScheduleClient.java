package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="wmsScheduleClient",name="oms-supplychain",configuration=FulfillmentScheduleFeignConfig.class)
public interface WmsScheduleClient {
    @PostMapping("/shipmentWms/internal/schedule/inbound") R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
