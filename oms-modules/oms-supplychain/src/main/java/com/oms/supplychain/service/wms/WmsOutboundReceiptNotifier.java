package com.oms.supplychain.service.wms;
import com.oms.common.task.CommittedWork;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
@Component
public class WmsOutboundReceiptNotifier {
    @Resource private OrderReceiptWakeupClient orders;
    /** Only notifies the order-side executor. Never waits for stock posting. */
    public void accepted(String company,long event){CommittedWork.afterCommit(()->{R<Map<String,Object>> reply;try{reply=orders.ready(company,event,SecurityConstants.INNER);}catch(feign.FeignException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Receipt wakeup failed company={} event={} http_status={} type={}",company,event,e.status(),e.getClass().getSimpleName());throw e;}if(reply==null||!R.isSuccess(reply)||reply.getData()==null||!Boolean.TRUE.equals(reply.getData().get("accepted")))throw new IllegalStateException("即时记账暂未唤醒，原事件等待补偿");});}
}
