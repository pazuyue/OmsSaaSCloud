package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class ExchangeFlowMessage implements MessageHandler {
    @Resource ExchangeFlowAutomation flow;
    @Resource ExchangePlatformClient platform;
    public String type(){return "EXCHANGE_FLOW";}
    public Map<String,Object> context(String c,Map<String,Object> m){return map("path","/oms-order/exchanges","id",m.get("business_id"));}
    static boolean platformWork(Map<String,Object> e){return "PLATFORM".equals(text(e,"source_kind"))&&(Arrays.asList("PENDING","STOCK_WAIT","SENDING","UNKNOWN").contains(text(e,"platform_operation"))||"13".equals(text(e,"platform_status")));}
    public List<String> rateKeys(String c,Map<String,Object> m){long id=number(m,"business_id");List<Long> versions=flow.repository.db().queryForList("SELECT work_version FROM oms_exchange_flow_task WHERE company_code=? AND exchange_id=?",Long.class,c,id);if(versions.isEmpty()||versions.get(0)!=number(m,"business_version"))return Collections.emptyList();Map<String,Object> e=flow.exchanges.row(c,id,false);return !platformWork(e)?Collections.emptyList():data(platform.rateKeys(c,number(e,"channel_id"),com.ruoyi.common.core.constant.SecurityConstants.INNER));}

    public Outcome handle(String c,Map<String,Object> m){Map<String,Object> r=flow.step(c,number(m,"business_id"),number(m,"business_version"));return "WAITING".equals(text(r,"state"))?OrderPullMessage.waiting(text(r,"message"),Arrays.asList("AUTO_REVIEWED","AGREE_SUBMITTED","QC_PASSED","RECEIVED_SUBMITTED","CARRIER_MATCHED","DISPATCH_SUBMITTED","CONSIGN_SUBMITTED","CONTINUE_APPROVED").contains(text(r,"code"))?2:30):Outcome.done(text(r,"message"));}
    public void failed(String c,Map<String,Object> m,String reason){}
    public void replay(String c,Map<String,Object> m,String actor,String reason){flow.retry(c,number(m,"business_id"));}
}
