package com.oms.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.tenant.TenantRouting;
import com.ruoyi.common.core.messaging.QueueKind;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.rabbitmq.client.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.messaging.MessageOutbox.*;

/** Small bounded quarantine drain. ACK only after durable control-store confirmation; never executes business. */
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class DeadLetterCollector {
 @Resource RabbitTemplate rabbit;@Resource QueueControlClient control;@Resource MessageOutbox outbox;@Resource MessageRuntime runtime;@Resource TenantRouting tenants;
 final ObjectMapper json=new ObjectMapper();
 @Scheduled(fixedDelay=20000,initialDelay=30000) public void tick(){try{for(QueueKind k:QueueKind.service(outbox.service()))drain(k);reconcile();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dead letter inspection deferred service={}",outbox.service());}finally{TenantRouting.clearContext();}}
 void drain(QueueKind k){rabbit.execute(channel->{for(int i=0;i<10;i++){GetResponse response=channel.basicGet(k.dead(),false);if(response==null)break;long tag=response.getEnvelope().getDeliveryTag();try{report(inspect(k,response));channel.basicAck(tag,false);}catch(Exception e){channel.basicNack(tag,false,true);break;}finally{TenantRouting.clearContext();}}return null;});}
 @SuppressWarnings("unchecked") Map<String,Object> inspect(QueueKind k,GetResponse response)throws Exception {
  Map<String,Object> r=new LinkedHashMap<>();r.put("service",outbox.service());r.put("business_type",k.name());r.put("company_code","");r.put("event_id","");r.put("business_number","");r.put("latest_event_id","");r.put("business_state","UNKNOWN");r.put("verified",false);
  Object deaths=response.getProps().getHeaders()==null?null:response.getProps().getHeaders().get("x-death");String reason="UNVERIFIED_SOURCE",identity="";
  if(deaths instanceof List)for(Object v:(List<?>)deaths)if(v instanceof Map){Map<?,?> d=(Map<?,?>)v;if(k.queue.equals(String.valueOf(d.get("queue")))){String why=String.valueOf(d.get("reason"));if(Arrays.asList("rejected","expired","maxlen","delivery_limit").contains(why)){reason=why;identity=why+":"+d.get("count");break;}}}
  r.put("fingerprint",hash(k.name()+":"+Base64.getEncoder().encodeToString(response.getBody())+":"+identity));r.put("death_reason",reason);
  if(reason.equals("UNVERIFIED_SOURCE"))return r;
  Map<String,Object> envelope;try{require(response.getBody().length<=8192,"消息超出范围");envelope=json.readValue(response.getBody(),Map.class);require(envelope!=null,"消息为空");}catch(Exception invalid){r.put("death_reason","INVALID_ENVELOPE");return r;}
  String company;try{company=TenantRouting.company(s(envelope,"company_code"));Set<String> allowed="order".equals(outbox.service())?tenants.continuingCompanies():tenants.companies();require(allowed.contains(company),"公司不在当前服务处理范围");}catch(IllegalArgumentException invalid){r.put("death_reason","INVALID_ENVELOPE");return r;}
  // Route and database availability errors propagate; the broker message remains unacknowledged/requeued.
  try(TenantRouting.Binding ignored=runtime.scope(company)){Map<String,Object> original;
   try{original=outbox.row(company,s(envelope,"event_id"));outbox.validate(envelope,original);require(k.name().equals(s(original,"message_type")),"队列业务不一致");}catch(IllegalArgumentException invalid){r.put("death_reason","INVALID_ENVELOPE");return r;}
   r.put("verified",true);r.put("company_code",company);r.put("event_id",s(original,"event_id"));r.put("business_number",s(original,"business_number"));snapshot(r,company,original);return r;
  }
 }
 void snapshot(Map<String,Object> r,String c,Map<String,Object> original){List<Map<String,Object>> chain=outbox.chain(c,original);Map<String,Object> latest=chain.isEmpty()?original:chain.get(0);r.put("business_state",s(latest,"process_state"));r.put("latest_event_id",s(latest,"event_id"));}
 void report(Map<String,Object> r){R<Boolean> result=control.deadReport(r,SecurityConstants.INNER);require(result!=null&&R.isSuccess(result)&&Boolean.TRUE.equals(result.getData()),"死信留痕未确认");}
 void reconcile(){Map<String,Object> request=new HashMap<>();request.put("service",outbox.service());R<List<Map<String,Object>>> response=control.deadChecks(request,SecurityConstants.INNER);require(response!=null&&R.isSuccess(response)&&response.getData()!=null,"死信业务核对不可用");for(Map<String,Object> r:response.getData()){r.put("service",outbox.service());r.put("verified",true);String c=s(r,"company_code");try(TenantRouting.Binding ignored=runtime.scope(c)){snapshot(r,c,outbox.row(c,s(r,"event_id")));}catch(RuntimeException unavailable){r.put("business_state","UNAVAILABLE");}finally{TenantRouting.clearContext();}report(r);}}
}
