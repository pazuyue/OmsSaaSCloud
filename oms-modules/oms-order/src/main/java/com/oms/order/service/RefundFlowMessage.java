package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class RefundFlowMessage implements MessageHandler {
 @Resource RefundFlowAutomation flow;
 public List<String> rateKeys(String c,Map<String,Object> message){Map<String,Object> row=flow.refunds.business(c,number(message,"business_id"),false);return data(flow.execution.channel.rateKeys(c,number(row,"channel_id"),com.ruoyi.common.core.constant.SecurityConstants.INNER));}
 public String type(){return "REFUND_FLOW";}
 public Map<String,Object> context(String c,Map<String,Object> message){return m("path","/oms-order/businessRefunds","id",message.get("business_id"));}
 public Outcome handle(String c,Map<String,Object> message){Map<String,Object> result=flow.step(c,number(message,"business_id"),number(message,"business_version"));return "WAITING".equals(text(result,"state"))?OrderPullMessage.waiting(text(result,"message"),30):Outcome.done(text(result,"message"));}
 public void failed(String c,Map<String,Object> message,String reason){}
 public void replay(String c,Map<String,Object> message,String actor,String reason){flow.retry(c,number(message,"business_id"));}
}
