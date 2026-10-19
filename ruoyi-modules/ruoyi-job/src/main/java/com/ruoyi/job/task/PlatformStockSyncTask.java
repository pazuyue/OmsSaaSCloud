package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@Component("platformStockSyncTask") public class PlatformStockSyncTask {
 @Resource PlatformStockSyncClient client;
 public void scanAll(){R<Map<String,Object>> result=client.scan(SecurityConstants.INNER);if(result==null||!R.isSuccess(result))throw new IllegalStateException("平台库存同步扫描失败");}
}
@FeignClient(contextId="platformStockSyncClient",name="oms-channel") interface PlatformStockSyncClient {
 @PostMapping("/stockSync/internal/scan-all") R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
}
