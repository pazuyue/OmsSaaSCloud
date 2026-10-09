package com.ruoyi.job.task;

import org.springframework.stereotype.Component;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import javax.annotation.Resource;
import java.util.Map;

/** Registered in the existing Quartz task UI as dailyAllocationTask.scan('qm'). */
@Component("dailyAllocationTask")
public class DailyAllocationTask {
    @Resource private DailyAllocationClient client;
    public void scan(String company) {
        R<Map<String,Object>> result=client.scan(SecurityConstants.INNER,company);
        if(result==null || result.getCode()!=200)throw new IllegalStateException(result==null?"库存调度无响应":result.getMsg());
    }
}
