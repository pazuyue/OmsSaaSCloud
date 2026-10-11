package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
@Component("orderPullTask")
public class OrderPullTask {
    @Resource private OrderPullClient client;
    public void scanAll(){R<Map<String,Object>> result=client.scan(SecurityConstants.INNER);if(result==null||!R.isSuccess(result))throw new IllegalStateException("订单拉取扫描调用失败");}
}
