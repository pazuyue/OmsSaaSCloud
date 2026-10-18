package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@Component("refundTask") public class RefundTask {
    @Resource private RefundScanClient client;
    public void scanAll(){R<Map<String,Object>> result=client.scan(SecurityConstants.INNER);if(result==null||!R.isSuccess(result))throw new IllegalStateException("退款同步扫描调用失败");}
}
@FeignClient(contextId="refundScanClient",name="oms-order") interface RefundScanClient {
    @PostMapping("/refunds/internal/scan-all") R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
