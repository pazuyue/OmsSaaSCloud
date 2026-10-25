package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class RefundApiDetailMessage implements MessageHandler {
 @Resource RefundApiSupplement api;
 public List<String> rateKeys(String c,Map<String,Object> message){Map<String,Object> row=api.refunds.repository.db().queryForMap("SELECT channel_id FROM oms_refund_api_item WHERE company_code=? AND id=?",c,number(message,"business_id"));return data(api.platform.channel.rateKeys(c,number(row,"channel_id"),com.ruoyi.common.core.constant.SecurityConstants.INNER));}
 public String type(){return "REFUND_API_DETAIL";}
 public Outcome handle(String c,Map<String,Object> message){Map<String,Object> result=api.detail(c,number(message,"business_id"));return "DONE".equals(text(result,"state"))?Outcome.done(text(result,"message")):Outcome.retry(text(result,"message"),120);}
 public void replay(String c,Map<String,Object> message,String actor,String reason){long id=number(message,"business_id"),version=number(message,"business_version")+1;Map<String,Object> row=api.refunds.repository.db().queryForMap("SELECT refund_id FROM oms_refund_api_item WHERE company_code=? AND id=?",c,id);api.refunds.repository.messaging().enqueue(c,type(),id,version,text(row,"refund_id"),id+":"+version,0);}
}
