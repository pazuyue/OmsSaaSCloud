package com.oms.messaging;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="queueControlClient",name="ruoyi-system") public interface QueueControlClient {
 @PostMapping("/mqQueues/internal/dead-report") R<Boolean> deadReport(@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
 @PostMapping("/mqQueues/internal/dead-checks") R<List<Map<String,Object>>> deadChecks(@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
 @PostMapping("/mqQueues/internal/snapshot") R<List<Map<String,Object>>> snapshot(@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
 @PostMapping("/mqQueues/internal/heartbeat") R<Boolean> heartbeat(@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
 @PostMapping("/mqQueues/internal/rate") R<Boolean> rate(@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
}
