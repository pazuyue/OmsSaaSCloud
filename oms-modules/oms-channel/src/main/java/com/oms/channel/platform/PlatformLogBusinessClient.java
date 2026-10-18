package com.oms.channel.platform;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="platformLogBusinessClient",name="oms-order")
public interface PlatformLogBusinessClient {
    @PostMapping("/platformLogBusiness/internal/resolve") R<List<Map<String,Object>>> resolve(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/platformLogBusiness/internal/states") R<List<Map<String,Object>>> states(@RequestParam("company_code") String company,@RequestBody List<Map<String,Object>> refs,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
