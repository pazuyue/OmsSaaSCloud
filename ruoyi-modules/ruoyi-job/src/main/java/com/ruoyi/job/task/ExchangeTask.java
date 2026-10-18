package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@Component("exchangeTask") public class ExchangeTask {
    @Resource private ExchangeScanClient client;
    public void scanAll(){R<Map<String,Object>> result=client.scan(SecurityConstants.INNER);if(result==null||!R.isSuccess(result))throw new IllegalStateException("换货扫描调用失败");}
}
@FeignClient(contextId="exchangeScanClient",name="oms-order")
interface ExchangeScanClient {
    @PostMapping("/exchanges/internal/scan-all") R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
