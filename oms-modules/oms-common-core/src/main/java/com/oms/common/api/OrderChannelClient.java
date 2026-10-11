package com.oms.common.api;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="orderChannelClient",name="oms-channel")
public interface OrderChannelClient {
    @GetMapping("/orderCatalog/internal/channels")
    R<List<Map<String,Object>>> channels(@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
