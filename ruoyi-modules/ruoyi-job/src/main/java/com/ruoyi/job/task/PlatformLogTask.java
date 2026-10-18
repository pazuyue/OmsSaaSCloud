package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@Component("platformLogTask") public class PlatformLogTask {
    @Resource PlatformLogMaintenanceClient client;
    public void purgeAll(){R<Map<String,Object>> result=client.purge(SecurityConstants.INNER);if(result==null||!R.isSuccess(result))throw new IllegalStateException("平台日志保留期清理失败");}
}
@FeignClient(contextId="platformLogMaintenanceClient",name="oms-channel")
interface PlatformLogMaintenanceClient {
    @PostMapping("/platform/log-maintenance/internal/purge-all") R<Map<String,Object>> purge(@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
