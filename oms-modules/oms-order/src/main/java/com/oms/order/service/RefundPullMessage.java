package com.oms.order.service;

import com.oms.messaging.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** A delivery reads at most one JDP page. Facts, conversion notifications and cursor commit together. */
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class RefundPullMessage implements MessageHandler {
 @Resource RefundSync sync;
 public String type(){return "REFUND_PULL";}
 public Map<String,Object> context(String c,Map<String,Object> m){Map<String,Object> t=sync.rawTask(c,number(m,"business_id"),false);return RefundValues.m("path","/oms-order/management","kind","REFUND","tab","tasks","taskId",t.get("id"));}
 public Outcome handle(String c,Map<String,Object> m){long id=number(m,"business_id"),version=number(m,"business_version");Map<String,Object> t=sync.rawTask(c,id,false);if(number(t,"work_version")!=version)return Outcome.done("退款同步已有新处理版本，原通知结束");if("COMPLETED".equals(text(t,"state")))return Outcome.done("平台退款拉取完成；转单进度独立展示");if("FAILED".equals(text(t,"state")))return Outcome.failed(text(t,"last_error"));long channel=number(t,"channel_id");String lease=token();if(!sync.claim(c,channel,lease))return OrderPullMessage.waiting("同店铺退款正在处理，等待租约释放",5);
  try{Map<String,Object> task=sync.refunds.tx().execute(s->{sync.fence(c,channel,lease);Map<String,Object> r=sync.rawTask(c,id,true);if(number(r,"work_version")!=version||!Arrays.asList("PENDING","RUNNING","RETRY").contains(text(r,"state")))return r;sync.refunds.repository.db().update("UPDATE oms_refund_sync SET state='RUNNING' WHERE company_code=? AND id=?",c,id);return r;});
   if(number(task,"work_version")!=version||!Arrays.asList("PENDING","RUNNING","RETRY").contains(text(task,"state")))return Outcome.done("原退款同步任务已结束");Map<String,Object> config=sync.config(c,channel,false);sync.catalog.validateShop(c,channel);require(text(task,"source_key").equals(text(config,"source_key"))&&text(task,"seller_nick").equals(text(config,"seller_nick")),"店铺退款来源已变化，请核对原任务");if("IDS".equals(text(task,"mode")))task.put("selected_ids",strings(task.get("selected_ids")));
   Map<String,Object> metadata=new LinkedHashMap<>(task);metadata.put("task_id",id);Map<String,Object> log=data(sync.logs.start(c,metadata,SecurityConstants.INNER));long started=System.nanoTime();boolean committed=false;
   try{List<Map<String,Object>> rows=sync.reader.readRefund(c,text(task,"source_key"),text(task,"seller_nick"),task,50);sync.commit(c,channel,lease,task,rows);committed=true;List<Map<String,Object>> refs=new ArrayList<>();for(Map<String,Object> row:rows)refs.add(RefundValues.m("type","PLATFORM_REFUND","number",text(row,"refund_id")));Map<String,Object> result=new LinkedHashMap<>(log);result.putAll(RefundValues.m("result","SUCCESS","records",rows.size(),"refs",refs,"duration_ms",(System.nanoTime()-started)/1000000));data(sync.logs.finish(c,result,SecurityConstants.INNER));}
   catch(RuntimeException error){Map<String,Object> result=new LinkedHashMap<>(log);result.putAll(RefundValues.m("result",committed?"UNKNOWN":"FAILED","error_code",committed?"SYNC_LOG_UNCONFIRMED":"REFUND_SOURCE_READ_FAILED","records",0,"refs",Collections.emptyList(),"duration_ms",(System.nanoTime()-started)/1000000));try{data(sync.logs.finish(c,result,SecurityConstants.INNER));}catch(RuntimeException ignored){}if(!committed)throw error;}
   return "COMPLETED".equals(text(sync.rawTask(c,id,false),"state"))?Outcome.done("平台退款已保存，转单由独立队列处理"):Outcome.again("本页平台退款、转单通知与游标已提交，继续下一页",5);
  }catch(RuntimeException error){String reason=MessageProblem.summary(error);sync.refunds.repository.db().update("UPDATE oms_refund_sync SET attempts=attempts+1,last_error=? WHERE company_code=? AND id=? AND work_version=? AND state IN ('PENDING','RUNNING','RETRY')",reason,c,id,version);sync.refunds.repository.db().update("UPDATE oms_refund_config SET last_error=? WHERE company_code=? AND channel_id=? AND lease_token=?",reason,c,channel,lease);return Outcome.retry(reason,15).problem(MessageProblem.exception(error));}finally{sync.release(c,channel,lease);}
 }
 public void failed(String c,Map<String,Object> m,String reason){sync.refunds.repository.db().update("UPDATE oms_refund_sync SET state='FAILED',last_error=? WHERE company_code=? AND id=? AND work_version=? AND state IN ('PENDING','RUNNING','RETRY')",reason.substring(0,Math.min(480,reason.length())),c,number(m,"business_id"),number(m,"business_version"));}
 public void replay(String c,Map<String,Object> m,String actor,String reason){sync.retry(c,number(m,"business_id"));}
}
