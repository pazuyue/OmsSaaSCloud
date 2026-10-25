package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class RefundPlatformMessage implements MessageHandler {
 @Resource RefundPlatformExecution execution;
 public List<String> rateKeys(String c,Map<String,Object> message){Map<String,Object> row=execution.refunds.repository.db().queryForMap("SELECT b.channel_id FROM oms_refund_operation o JOIN oms_refund b ON b.company_code=o.company_code AND b.id=o.refund_id WHERE o.company_code=? AND o.id=?",c,number(message,"business_id"));return data(execution.channel.rateKeys(c,number(row,"channel_id"),com.ruoyi.common.core.constant.SecurityConstants.INNER));}
 public String type(){return "REFUND_PLATFORM";}
 public Outcome handle(String c,Map<String,Object> message){try{Map<String,Object> result=execution.reconcileOperation(c,number(message,"business_id"));return "DONE".equals(text(result,"state"))?Outcome.done(text(result,"message")):OrderPullMessage.waiting(text(result,"message"),120);}catch(RuntimeException unavailable){return OrderPullMessage.waiting("平台查询暂未完成，保留原操作并稍后核对",120);}}
 public void failed(String c,Map<String,Object> message,String reason){}
 public void replay(String c,Map<String,Object> message,String actor,String reason){long id=number(message,"business_id"),version=number(message,"business_version")+1;Map<String,Object> intent=execution.refunds.repository.db().queryForMap("SELECT request_id FROM oms_refund_operation WHERE company_code=? AND id=?",c,id);execution.refunds.repository.messaging().enqueue(c,type(),id,version,text(intent,"request_id"),id+":"+version,0);}
}
