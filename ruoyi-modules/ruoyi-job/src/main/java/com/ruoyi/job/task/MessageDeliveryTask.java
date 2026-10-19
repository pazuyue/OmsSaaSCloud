package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;import java.util.*;
@Component("messageDeliveryTask") public class MessageDeliveryTask {
 @Resource ChannelMessageClient channel;@Resource OrderMessageClient order;
 public void channel(){verify(channel.scan(SecurityConstants.INNER));}
 public void order(){verify(order.scan(SecurityConstants.INNER));}
 void verify(R<Map<String,Object>> r){if(r==null||!R.isSuccess(r)||r.getData().containsValue("FAILED"))throw new IllegalStateException("消息补偿扫描存在未处理的公司，请查看消息任务");}
}
@FeignClient(contextId="channelMessageClient",name="oms-channel") interface ChannelMessageClient {
 @PostMapping("/messages/internal/compensate") R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
}
@FeignClient(contextId="orderMessageClient",name="oms-order") interface OrderMessageClient {
 @PostMapping("/messages/internal/compensate") R<Map<String,Object>> scan(@RequestHeader(SecurityConstants.FROM_SOURCE)String source);
}
