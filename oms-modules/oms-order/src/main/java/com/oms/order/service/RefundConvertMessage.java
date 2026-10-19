package com.oms.order.service;

import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.m;

/** Ingestion is not consent, payment, cancellation or a warehouse receipt. */
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class RefundConvertMessage implements MessageHandler {
 @Resource RefundService refunds;@Resource RefundSync sync;
 public String type(){return "REFUND_CONVERT";}
 public Map<String,Object> context(String c,Map<String,Object> m){Map<String,Object> p=refunds.platform(c,number(m,"business_id"),false);return m("path","/oms-order/platformDocuments","type","REFUND","id",p.get("id"),"platformTid",p.get("platform_tid"));}
 public Outcome handle(String c,Map<String,Object> m){long id=number(m,"business_id"),version=number(m,"business_version");Map<String,Object> p=refunds.platform(c,id,false);if(number(p,"intake_version")!=version)return Outcome.done("平台退款已有新版本，原通知结束");if(p.get("business_refund_id")!=null)return Outcome.done("原平台退款已生成业务退款单，无需重复创建");long channel=number(p,"channel_id");String lease=token();if(!sync.claim(c,channel,lease))return OrderPullMessage.waiting("同店铺退款正在处理，等待租约释放",5);
  try{return refunds.tx().execute(s->{Map<String,Object> hint=refunds.platform(c,id,false);refunds.lockOriginal(c,channel,text(hint,"platform_tid"));Map<String,Object> current=refunds.platform(c,id,true);if(number(current,"intake_version")!=version)return Outcome.done("平台退款已有新版本，原通知结束");if(current.get("business_refund_id")!=null)return Outcome.done("业务退款已存在，无需重复创建");sync.fence(c,channel,lease);if(!"VALID".equals(text(current,"source_state")))return Outcome.failed("平台来源存在异常，请先核对退款资料");if(number(current,"conversion_requested")!=1&&number(sync.config(c,channel,false),"auto_convert")!=1)return OrderPullMessage.waiting("店铺未开启自动转单；可开启设置或从平台退款主动转单",30);
   Map<String,Object> result=refunds.convert(c,id,"队列转单");String state=text(result,"state");if("SUCCESS".equals(state))return Outcome.done("已生成业务退款单，等待审核；本次未执行资金或货物操作");if("SKIPPED".equals(state))return Outcome.done("平台退款已关闭，未创建业务退款单");if("WAITING".equals(state))return OrderPullMessage.waiting(text(result,"message"),30).problem("BUSINESS_CONDITION");return Outcome.failed(text(result,"message"));});
  }finally{sync.release(c,channel,lease);}
 }
 public void failed(String c,Map<String,Object> m,String reason){refunds.repository.db().update("UPDATE oms_platform_refund SET conversion_state='FAILED',conversion_error=? WHERE company_code=? AND id=? AND intake_version=? AND business_refund_id IS NULL",reason.substring(0,Math.min(480,reason.length())),c,number(m,"business_id"),number(m,"business_version"));}
 public void replay(String c,Map<String,Object> m,String actor,String reason){refunds.requestConvert(c,number(m,"business_id"),actor);}
}
