package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@Component("platformProductTask") public class PlatformProductTask {
 @Resource PlatformProductPullClient client;
 public void scanAll(){R<Map<String,Object>> r=client.scan(SecurityConstants.INNER);if(r==null||!R.isSuccess(r))throw new IllegalStateException("平台商品拉取扫描失败");}
}
@FeignClient(contextId="platformProductPullClient",name="oms-channel") interface PlatformProductPullClient {
 @PostMapping("/platformProducts/internal/scan-all") R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
}
