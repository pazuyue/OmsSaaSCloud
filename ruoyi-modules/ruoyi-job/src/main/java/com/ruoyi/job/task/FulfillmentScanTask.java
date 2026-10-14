package com.ruoyi.job.task;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.job.domain.JobScanResult;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
@Component("fulfillmentScanTask")
public class FulfillmentScanTask {
    @Resource private FulfillmentScanClient orders;
    @Resource private WmsScheduleClient warehouse;
    private JobScanResult scan(String kind){R<Map<String,Object>> r="inbound".equals(kind)?warehouse.scan(SecurityConstants.INNER):orders.scan(kind,SecurityConstants.INNER);if(r==null||!R.isSuccess(r))throw new IllegalStateException("业务扫描服务调用未确认，请查看对应服务日志");return new JobScanResult(kind,r.getData());}
    public JobScanResult dispatch(){return scan("dispatch");}
    public JobScanResult receipts(){return scan("receipts");}
    public JobScanResult virtualOutbound(){return scan("virtual");}
    public JobScanResult salesDocuments(){return scan("sales");}
    public JobScanResult platformShipping(){return scan("platform");}
    public JobScanResult orderIssues(){return scan("issues");}
    public JobScanResult purchaseInbound(){return scan("inbound");}
}
