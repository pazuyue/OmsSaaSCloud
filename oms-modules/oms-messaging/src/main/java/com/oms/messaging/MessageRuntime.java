package com.oms.messaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.tenant.TenantRouting;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import com.rabbitmq.client.Channel;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.oms.messaging.MessageOutbox.*;
/** Broker messages are wakeups. Durable company-scoped work remains authoritative. */
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true") public class MessageRuntime {
 @Resource MessageOutbox outbox;@Resource TenantRouting tenants;@Resource RabbitTemplate rabbit;
 @Resource List<MessageHandler> handlers;
 @org.springframework.beans.factory.annotation.Autowired org.springframework.beans.factory.ObjectProvider<QueueSupervisor> supervisors;
 final java.util.concurrent.ThreadPoolExecutor publishers=new java.util.concurrent.ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(12),r->{Thread t=new Thread(r,"mq-publisher");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
 final java.util.concurrent.atomic.AtomicInteger rotation=new java.util.concurrent.atomic.AtomicInteger();
 QueueSupervisor supervisor(){return supervisors==null?null:supervisors.getIfAvailable();}
 boolean intake(String type){return Arrays.asList("ORDER_PULL","ORDER_CONVERT","REFUND_PULL","REFUND_CONVERT","EXCHANGE_PULL","EXCHANGE_CONVERT").contains(type);}
 boolean enabled(String type){return supervisor()==null||supervisor().enabled(type);}
 Map<String,Object> policy(String type){return supervisor()==null?Collections.emptyMap():supervisor().policy(type);}
 long setting(String type,String key,long fallback){Map<String,Object> p=policy(type);return p.containsKey(key)?n(p,key):fallback;}
 List<String> companies(){List<String> c=new ArrayList<>("order".equals(outbox.service())?tenants.continuingCompanies():tenants.companies());if(!c.isEmpty())Collections.rotate(c,-Math.floorMod(rotation.getAndIncrement(),c.size()));return c;}
 List<com.ruoyi.common.core.messaging.QueueKind> kinds(){List<com.ruoyi.common.core.messaging.QueueKind> kinds=new ArrayList<>(com.ruoyi.common.core.messaging.QueueKind.service(outbox.service()));Collections.rotate(kinds,-Math.floorMod(rotation.get(),kinds.size()));return kinds;}
 @javax.annotation.PreDestroy public void close(){publishers.shutdown();}

 private final ObjectMapper json=new ObjectMapper();
 MessageHandler handler(String type){return handlers.stream().filter(h->h.type().equals(type)).findFirst().orElseThrow(()->new IllegalArgumentException("未注册的业务消息类型"));}
 TenantRouting.Binding scope(String c){return "order".equals(outbox.service())?tenants.openForContinuation(c):tenants.open(c);}
 @Scheduled(fixedDelayString="${oms.messaging.poll-ms:2000}",initialDelay=15000)
 public void tick(){Set<String> intakeCompanies=tenants.companies();for(String c:companies())try(TenantRouting.Binding ignored=scope(c)){for(com.ruoyi.common.core.messaging.QueueKind kind:kinds()){if(!enabled(kind.name())||(intake(kind.name())&&!intakeCompanies.contains(c)))continue;for(Map<String,Object> r:outbox.db.queryForList("SELECT * FROM oms_message_outbox WHERE company_code=? AND service_code=? AND message_type=? AND process_state IN ('PENDING','WAITING','PROCESSING') AND next_publish_at<=NOW(3) AND (publish_state IN ('PENDING','UNCERTAIN') OR (publish_state='SENDING' AND publish_until<NOW(3)) OR (publish_state='PUBLISHED' AND last_published_at<DATE_SUB(NOW(3),INTERVAL 120 SECOND))) ORDER BY next_publish_at,id LIMIT 4",c,outbox.service(),kind.name())){TenantRouting.Route route="order".equals(outbox.service())&&!intake(kind.name())?tenants.captureForContinuation(c):tenants.capture(c);try{publishers.execute(()->{try(TenantRouting.Binding worker=route.open()){if(enabled(kind.name()))publish(c,r);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Message publisher deferred service={}",outbox.service());}finally{TenantRouting.clearContext();}});}catch(java.util.concurrent.RejectedExecutionException full){return;}}}}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Message delivery scan unavailable service={} company={}",outbox.service(),c);}finally{TenantRouting.clearContext();}}
 /** Quartz compensation obeys the same per-business admission and resource budget as MQ. */
 public Map<String,Object> compensate(){Set<String> intakeCompanies=tenants.companies();Map<String,Object> report=new LinkedHashMap<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);for(String c:companies()){if(System.nanoTime()>deadline)break;try(TenantRouting.Binding ignored=scope(c)){int count=0;for(com.ruoyi.common.core.messaging.QueueKind kind:kinds()){if(System.nanoTime()>deadline)break;String type=kind.name();if(!enabled(type)||(intake(type)&&!intakeCompanies.contains(c)))continue;for(Map<String,Object> r:outbox.db.queryForList("SELECT * FROM oms_message_outbox WHERE company_code=? AND service_code=? AND message_type=? AND process_state IN ('PENDING','WAITING','PROCESSING') AND next_process_at<=DATE_SUB(NOW(3),INTERVAL ? SECOND) AND (work_until IS NULL OR work_until<NOW(3)) ORDER BY next_process_at,id LIMIT ?",c,outbox.service(),type,setting(type,"compensate_after_sec",60),setting(type,"compensate_batch",2))){if(System.nanoTime()>deadline)break;if(execute(c,r,"COMPENSATION"))count++;}}report.put(c,count);}catch(RuntimeException e){report.put(c,"FAILED");org.slf4j.LoggerFactory.getLogger(getClass()).warn("Message compensation unavailable service={} company={}",outbox.service(),c);}finally{TenantRouting.clearContext();}}return report;}
 public void publish(String c,Map<String,Object> r){String event=s(r,"event_id"),lease=token();if(outbox.db.update("UPDATE oms_message_outbox SET publish_state='SENDING',publish_token=?,publish_until=DATE_ADD(NOW(3),INTERVAL 15 SECOND),send_attempts=send_attempts+1 WHERE company_code=? AND service_code=? AND event_id=? AND process_state IN ('PENDING','WAITING','PROCESSING') AND next_publish_at<=NOW(3) AND (publish_until IS NULL OR publish_until<NOW(3)) AND (publish_state IN ('PENDING','UNCERTAIN','SENDING') OR (publish_state='PUBLISHED' AND last_published_at<DATE_SUB(NOW(3),INTERVAL 120 SECOND)))",lease,c,outbox.service(),event)!=1)return;
  long start=System.nanoTime();String state="UNCERTAIN",error="消息代理暂不可用，将保留原任务重试";
  try{CorrelationData data=new CorrelationData(event+":"+lease);MessageProperties p=new MessageProperties();p.setContentType("application/json");p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);p.setMessageId(event);p.setCorrelationId(s(r,"trace_id"));rabbit.send("oms.work.v1",s(r,"message_type"),new Message(json.writeValueAsBytes(outbox.envelope(r)),p),data);CorrelationData.Confirm confirm=data.getFuture().get(3,TimeUnit.SECONDS);if(confirm.isAck()&&data.getReturned()==null){state="PUBLISHED";error="";}else error=data.getReturned()!=null?"消息路由不存在，等待修复拓扑后重试":"消息代理未确认接收，保留原任务重试";}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
  final String result=state,description=error;outbox.tx(()->{int changed=outbox.db.update("UPDATE oms_message_outbox SET publish_state=?,last_publish_error=?,last_published_at=IF(?='PUBLISHED',NOW(3),last_published_at),next_publish_at=DATE_ADD(NOW(3),INTERVAL 15 SECOND),publish_token=NULL,publish_until=NULL WHERE company_code=? AND service_code=? AND event_id=? AND publish_token=?",result,description,result,c,outbox.service(),event,lease);
   // A fast consumer may finish before the publisher confirm is persisted. Keep both facts.
   if(changed==0&&result.equals("PUBLISHED"))outbox.db.update("UPDATE oms_message_outbox SET last_published_at=NOW(3) WHERE company_code=? AND service_code=? AND event_id=?",c,outbox.service(),event);
   outbox.attempt(c,event,"PUBLISH",result,"MQ",description,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),"消息发布器");return null;});
 }
 public void consume(Message message,Channel channel)throws java.io.IOException{consume(null,message,channel);}
 @SuppressWarnings("unchecked") public void consume(String expectedType,Message message,Channel channel)throws java.io.IOException {long tag=message.getMessageProperties().getDeliveryTag();boolean valid=false;try{require(message.getBody().length<=8192,"消息体超出范围");Map<String,Object> e=json.readValue(message.getBody(),Map.class);String c=TenantRouting.company(s(e,"company_code"));try(TenantRouting.Binding ignored=scope(c)){Map<String,Object> r=outbox.row(c,s(e,"event_id"));outbox.validate(e,r);require(expectedType==null||expectedType.equals(s(r,"message_type")),"消息投递到了其他业务队列");handler(s(r,"message_type"));valid=true;if(!execute(c,r,"MQ"))defer(c,s(r,"event_id"));}channel.basicAck(tag,false);
 }catch(Exception e){if(valid){ // DB/broker failure is retried by the durable row, without a poison-message loop.
    channel.basicNack(tag,false,false);
   }else channel.basicReject(tag,false);
   org.slf4j.LoggerFactory.getLogger(getClass()).warn("Message rejected service={} validated={}",outbox.service(),valid);
 }finally{TenantRouting.clearContext();}}
 void defer(String c,String event){outbox.db.update("UPDATE oms_message_outbox SET publish_state='PENDING',publish_token=NULL,publish_until=NULL,next_publish_at=GREATEST(next_process_at,DATE_ADD(NOW(3),INTERVAL 5 SECOND)) WHERE company_code=? AND service_code=? AND event_id=? AND process_state IN ('PENDING','WAITING') AND (work_until IS NULL OR work_until<NOW(3))",c,outbox.service(),event);}
 public boolean execute(String c,Map<String,Object> r,String mode){String event=s(r,"event_id"),lease=token(),type=s(r,"message_type");QueueSupervisor supervisor=supervisor();QueueSupervisor.Permit permit=supervisor==null?null:supervisor.enter(type);if(supervisor!=null&&permit==null)return false;
  try(TenantRouting.Binding admission=intake(type)?tenants.open(c):null){if(!outbox.claimWork(c,event,lease,mode))return false;long start=System.nanoTime();MessageHandler.Outcome outcome;long attempts=n(r,"business_attempts"),limit=setting(type,"retry_limit",8);
   try{MessageHandler handler=handler(type);if(supervisor!=null&&!supervisor.rate(type,handler.rateKeys(c,r)))outcome=MessageHandler.Outcome.throttled("等待平台调用额度或控制服务恢复",5);else outcome=handler.handle(c,r);}catch(Exception e){outcome=MessageHandler.Outcome.retry(MessageProblem.summary(e),Math.min(setting(type,"retry_max_sec",300),setting(type,"retry_base_sec",15)*(1L<<Math.min(8,attempts)))).problem(MessageProblem.exception(e));}
   if(outcome.state.equals("WAITING")&&!outcome.progress)outcome=MessageHandler.Outcome.retry(outcome.message,Math.min(setting(type,"retry_max_sec",300),setting(type,"retry_base_sec",15)*(1L<<Math.min(8,attempts)))).problem(outcome.problemCode);
   if(outcome.state.equals("WAITING")&&!outcome.progress&&attempts>=limit-1)outcome=MessageHandler.Outcome.failed("自动处理已达配置上限；最后原因："+outcome.message.substring(0,Math.min(outcome.message.length(),900))).problem("RETRY_EXHAUSTED");
   if(outcome.state.equals("FAILED"))handler(type).failed(c,r,outcome.message);
   outbox.finish(c,event,lease,outcome,mode,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));if(supervisor!=null)supervisor.record(type,outcome.state,mode,System.nanoTime()-start);return true;
  }finally{if(permit!=null)permit.close();}
 }
 public void retry(String c,String event,String actor,String reason){require(!reason.trim().isEmpty()&&reason.length()<=255,"请填写255字以内的重试依据");outbox.tx(()->{Map<String,Object> r=outbox.db.queryForMap("SELECT * FROM oms_message_outbox WHERE company_code=? AND service_code=? AND event_id=? FOR UPDATE",c,outbox.service(),event);require("FAILED".equals(s(r,"process_state")),"仅处理失败的消息可申请重试；待执行任务会自动推进");require(n(outbox.row(c,event),"has_newer")==0,"已有后续通知，请查看最新消息与业务结果");outbox.retryParent.set(event);try{handler(s(r,"message_type")).replay(c,r,actor,reason);}finally{outbox.retryParent.remove();}outbox.attempt(c,event,"RETRY","REQUESTED","MANUAL",reason,0,actor);return null;});}
}
