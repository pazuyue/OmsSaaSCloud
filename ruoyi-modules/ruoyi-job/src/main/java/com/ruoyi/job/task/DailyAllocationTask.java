package com.ruoyi.job.task;

import org.springframework.stereotype.Component;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import javax.annotation.Resource;
import java.util.Map;
import java.util.Collection;

/** One registered Quartz task discovers companies; no per-company task provisioning. */
@Component("dailyAllocationTask")
public class DailyAllocationTask {
    @Resource private DailyAllocationClient client;
    public void scanAll() {
        R<Map<String,Object>> result=client.scanAll(SecurityConstants.INNER);
        if(result==null || result.getCode()!=200)throw new IllegalStateException(result==null?"库存调度无响应":result.getMsg());
        Object failures=result.getData().get("failures");
        if(failures instanceof Collection && !((Collection<?>)failures).isEmpty())throw new IllegalStateException("已继续处理其他公司，以下数据源或公司待后续重试："+failures);
    }
}
