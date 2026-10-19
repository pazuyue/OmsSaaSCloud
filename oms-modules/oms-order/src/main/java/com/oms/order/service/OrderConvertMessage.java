package com.oms.order.service;

import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Notification versions invalidate old wakeups. Original business rules own demand and stock writes. */
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class OrderConvertMessage implements MessageHandler {
 @Resource OrderRepository repository;@Resource OrderProcessor processor;
 public String type(){return "ORDER_CONVERT";}
 public Map<String,Object> context(String c,Map<String,Object> m){Map<String,Object> p=repository.raw(c,number(m,"business_id")),r=new LinkedHashMap<>();r.put("path","/oms-order/platformDocuments");r.put("type","ORDER");r.put("id",p.get("id"));r.put("platformTid",p.get("platform_tid"));return r;}
 public Outcome handle(String c,Map<String,Object> m){long id=number(m,"business_id"),version=number(m,"business_version");Map<String,Object> row=repository.raw(c,id);if(number(row,"intake_version")!=version)return Outcome.done("平台订单已有更新的处理版本，原通知结束");if(row.get("business_order_id")!=null)return Outcome.done("原平台订单已生成业务订单，无需重复转单");long channel=number(row,"channel_id");String lease=token();if(!repository.claim(c,channel,lease))return OrderPullMessage.waiting("同店铺正在处理，等待店铺租约释放",5);
  try{row=repository.raw(c,id);if(number(row,"intake_version")!=version)return Outcome.done("平台订单已有更新的处理版本，原通知结束");if(row.get("business_order_id")!=null)return Outcome.done("业务订单已存在，无需重复转单");if(number(row,"change_pending")!=0)return Outcome.failed("平台订单更新待核对，请从订单更新处理原业务阻断");Map<String,Object> config=repository.config(c,channel);processor.process(c,id,lease,config,number(config,"auto_convert")==1,true);Map<String,Object> after=repository.raw(c,id);if(after.get("business_order_id")!=null)return Outcome.done("已生成业务订单；逻辑预占、审核和分仓沿用原业务流程");String state=text(after,"conversion_status"),error=text(after,"conversion_error"),code=text(after,"exception_code");if(code.equals("PRODUCT_SYNC_PENDING"))return OrderPullMessage.waiting("等待平台商品补拉与匹配后继续转单",30).problem("BUSINESS_CONDITION");if(code.equals("SYSTEM_FAILURE")&&state.equals("PENDING"))return Outcome.retry(error,15).problem("SERVICE_ERROR");if(state.equals("EXCEPTION"))return Outcome.failed(error.isEmpty()?"原订单转单条件未满足，请查看平台订单":error);if(state.equals("WAITING"))return Outcome.done(error.isEmpty()?"已保留平台订单，等待满足转单条件或人工申请":error);return OrderPullMessage.waiting("原转单任务仍在推进，稍后继续",5);
  }finally{repository.release(c,channel,lease);}
 }
 public void failed(String c,Map<String,Object> m,String reason){repository.db().update("UPDATE oms_platform_order SET conversion_status='EXCEPTION',conversion_error=?,exception_code=IF(exception_code='','SYSTEM_FAILURE',exception_code),next_retry_at=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE company_code=? AND id=? AND intake_version=? AND business_order_id IS NULL AND change_pending=0 AND conversion_status IN ('PENDING','PROCESSING')",reason.substring(0,Math.min(480,reason.length())),c,number(m,"business_id"),number(m,"business_version"));}
 public void replay(String c,Map<String,Object> m,String actor,String reason){List<Map<String,Object>> result=new OrderExceptionService(repository).retry(c,Collections.singletonList(number(m,"business_id")),actor);require(Boolean.TRUE.equals(result.get(0).get("submitted")),text(result.get(0),"message"));}
}
