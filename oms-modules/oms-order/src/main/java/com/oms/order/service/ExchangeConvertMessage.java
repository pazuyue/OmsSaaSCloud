package com.oms.order.service;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.map;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class ExchangeConvertMessage implements MessageHandler {
 @Resource PlatformExchangeService source;@Resource ExchangeSync sync;
 public String type(){return "EXCHANGE_CONVERT";}
 public Map<String,Object> context(String c,Map<String,Object> m){Map<String,Object> p=source.row(c,number(m,"business_id"),false);return map("path","/oms-order/platformDocuments","type","EXCHANGE","id",p.get("id"),"platformTid",p.get("platform_tid"));}
 public Outcome handle(String c,Map<String,Object> m){long id=number(m,"business_id"),v=number(m,"business_version");Map<String,Object> p=source.row(c,id,false);if(number(p,"intake_version")!=v)return Outcome.done("平台换货已有新版本，原通知结束");if(p.get("business_exchange_id")!=null)return Outcome.done("平台申请已关联原业务换货单，无需重复创建");long channel=number(p,"channel_id");String lease=token();if(!sync.claim(c,channel,lease))return OrderPullMessage.waiting("同店铺换货正在处理，等待租约释放",5);
  try{return source.tx().execute(s->{sync.fence(c,channel,lease);Map<String,Object> current=source.row(c,id,true);if(number(current,"intake_version")!=v)return Outcome.done("平台换货已有新版本，原通知结束");if(current.get("business_exchange_id")!=null)return Outcome.done("业务换货已存在，无需重复创建");if(!"VALID".equals(text(current,"source_state")))return Outcome.failed("平台来源异常，请先核对换货资料");if("SKIPPED".equals(text(current,"conversion_state")))return Outcome.done("平台换货已结束，未新增执行任务");if(number(current,"conversion_requested")!=1&&number(sync.config(c,channel),"auto_convert")!=1)return OrderPullMessage.waiting("店铺未开启自动转单；可开启设置或从平台换货主动转单",30);Map<String,Object> result=source.convert(c,id,"队列转单");String state=text(result,"state");if(state.equals("SUCCESS"))return Outcome.done("已关联业务换货单，等待审核；未同意换货或执行库存操作");if(state.equals("SKIPPED"))return Outcome.done("平台申请已结束，无需新增执行任务");if(state.equals("WAITING"))return OrderPullMessage.waiting(text(result,"message"),30).problem("BUSINESS_CONDITION");return Outcome.failed(text(result,"message"));});}finally{sync.release(c,channel,lease);}
 }
 public void failed(String c,Map<String,Object> m,String reason){source.repository.db().update("UPDATE oms_platform_exchange SET conversion_state='FAILED',conversion_error=? WHERE company_code=? AND id=? AND intake_version=? AND business_exchange_id IS NULL",reason.substring(0,Math.min(480,reason.length())),c,number(m,"business_id"),number(m,"business_version"));}
 public void replay(String c,Map<String,Object> m,String actor,String reason){source.requestConvert(c,number(m,"business_id"),actor);}
}
