package com.ruoyi.job.task;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Bean;
import java.util.Map;

@FeignClient(contextId="dailyAllocationClient",name="oms-inventory",configuration=DailyAllocationClient.Config.class)
public interface DailyAllocationClient {
    @PostMapping("/allocation/internal/daily-scan")
    R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE) String source,@RequestParam("company_code") String company);
    class Config {
        @Bean public feign.Request.Options dailyOptions(){return new feign.Request.Options(5000,45000);}
        @Bean public feign.Retryer dailyRetryer(){return feign.Retryer.NEVER_RETRY;}
    }
}
