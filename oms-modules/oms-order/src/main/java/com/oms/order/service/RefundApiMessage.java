package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class RefundApiMessage implements MessageHandler {
 @Resource RefundApiSupplement api;
 public List<String> rateKeys(String c,Map<String,Object> message){Map<String,Object> row=com.oms.order.service.RefundValues.m("channel_id",message.get("business_id"));return data(api.platform.channel.rateKeys(c,number(row,"channel_id"),com.ruoyi.common.core.constant.SecurityConstants.INNER));}
 public String type(){return "REFUND_API_PULL";}
 public Outcome handle(String c,Map<String,Object> message){Map<String,Object> result=api.page(c,number(message,"business_id"),number(message,"business_version"));return "DONE".equals(text(result,"state"))?Outcome.done(text(result,"message")):OrderPullMessage.waiting(text(result,"message"),30);}
 public void replay(String c,Map<String,Object> message,String actor,String reason){long channel=number(message,"business_id");com.oms.order.service.OrderValues.require(api.refunds.repository.db().update("UPDATE oms_refund_api_cursor SET work_version=work_version+1,next_run=NOW() WHERE company_code=? AND channel_id=? AND enabled=1 AND (lease_until IS NULL OR lease_until<NOW())",c,channel)==1,"普通接口同步已停止或正在处理，请核对店铺设置");api.scan(c);}
}
