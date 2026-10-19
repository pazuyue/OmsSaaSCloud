package com.oms.order.service;

import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** One committed source page per delivery. Pull and conversion share the same fenced shop lease. */
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class OrderPullMessage implements MessageHandler {
 @Resource OrderRepository repository;@Resource ConfiguredPushReader push;@Resource OrderCatalog catalog;
 public String type(){return "ORDER_PULL";}
 Map<String,Object> task(String c,long id){List<Map<String,Object>> rows=repository.db().query("SELECT * FROM oms_order_pull_task WHERE company_code=? AND id=?",ROW,c,id);require(rows.size()==1,"拉单任务不存在或不属于当前公司");return rows.get(0);}
 public Map<String,Object> context(String c,Map<String,Object> m){Map<String,Object> t=task(c,number(m,"business_id")),r=new LinkedHashMap<>();r.put("path","/oms-order/management");r.put("kind","ORDER");r.put("tab","tasks");r.put("taskId",t.get("id"));return r;}
 static Outcome waiting(String message,long seconds){Outcome out=Outcome.again(message,seconds);out.preserveAttempts=true;return out;}
 public Outcome handle(String c,Map<String,Object> m){long id=number(m,"business_id"),version=number(m,"business_version");Map<String,Object> initial=task(c,id);if(number(initial,"work_version")!=version)return Outcome.done("拉单任务已有新版本，原通知结束");String status=text(initial,"status");if(status.equals("SUCCESS"))return Outcome.done("拉取已完成；平台订单与转单任务独立保留");if(Arrays.asList("FAILED","PARTIAL").contains(status))return Outcome.failed(text(initial,"error_message").isEmpty()?"请从原拉单任务核对失败原因":text(initial,"error_message"));long channel=number(initial,"channel_id");String lease=token();if(!repository.claim(c,channel,lease))return waiting("同店铺正在处理，等待店铺租约释放",5);
  try{Map<String,Object> config=repository.config(c,channel);Map<String,Object> current=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource())).execute(t->{repository.fence(c,channel,lease);Map<String,Object> r=task(c,id);require(number(r,"work_version")==version,"拉单任务版本已变化");if(!Arrays.asList("PENDING","RUNNING").contains(text(r,"status")))return r;repository.db().update("UPDATE oms_order_pull_task SET status='RUNNING' WHERE company_code=? AND id=? AND work_version=?",c,id,version);if("TID".equals(text(r,"pull_mode")))r.put("selected_tids",repository.db().queryForList("SELECT platform_tid FROM oms_order_pull_item WHERE company_code=? AND task_id=? AND result='PENDING' ORDER BY id LIMIT 50",String.class,c,id));return r;});
   if(!Arrays.asList("PENDING","RUNNING").contains(text(current,"status")))return Outcome.done("原拉单任务已结束");catalog.validateShop(c,channel);if(!text(current,"pull_source_key").isEmpty())require(text(current,"pull_source_key").equals(text(config,"source_key"))&&text(current,"pull_seller_nick").equals(text(config,"seller_nick")),"店铺拉单来源已改变，请重新核对补拉范围");
   List<Map<String,Object>> rows="TID".equals(text(current,"pull_mode"))&&((List<?>)current.get("selected_tids")).isEmpty()?Collections.emptyList():push.read(c,text(config,"source_key"),text(config,"seller_nick"),current,50);repository.page(c,channel,lease,current,rows,50);Map<String,Object> after=task(c,id);String next=text(after,"status");if(next.equals("SUCCESS"))return Outcome.done("拉取已完成；每张平台订单独立转单");if(next.equals("PARTIAL"))return Outcome.failed(text(after,"error_message"));return Outcome.again("本页平台订单与游标已提交，继续下一页",5);
  }catch(RuntimeException e){String reason=MessageProblem.summary(e);repository.db().update("UPDATE oms_order_pull_task SET attempts=attempts+1,error_message=?,next_retry_at=NOW() WHERE company_code=? AND id=? AND work_version=? AND status IN ('PENDING','RUNNING')",reason,c,id,version);return Outcome.retry(reason,15).problem(MessageProblem.exception(e));}finally{repository.release(c,channel,lease);}
 }
 public void failed(String c,Map<String,Object> m,String reason){repository.db().update("UPDATE oms_order_pull_task SET status='FAILED',error_message=? WHERE company_code=? AND id=? AND work_version=? AND status IN ('PENDING','RUNNING')",reason.substring(0,Math.min(480,reason.length())),c,number(m,"business_id"),number(m,"business_version"));}
 public void replay(String c,Map<String,Object> m,String actor,String reason){repository.retryPull(c,number(m,"business_id"),actor);}
}
