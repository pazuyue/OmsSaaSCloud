package com.oms.order.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Only local document transactions. Remote reads and inventory calls live in OrderProcessor. */
@Service
public class OrderRepository {
    @Resource private JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<OrderImmediateWork> immediate;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.oms.messaging.MessageOutbox> messages;
    @org.springframework.transaction.annotation.Transactional
    public void shipmentChanged(String company,long shipment){
        jdbc.update("INSERT INTO oms_order_sales_document_sync(company_code,shipment_id,dirty_version) VALUES (?,?,1) ON DUPLICATE KEY UPDATE dirty_version=dirty_version+1,next_attempt=NOW()",company,shipment);
        com.oms.messaging.MessageOutbox outbox=messages==null?null:messages.getIfAvailable();
        if(outbox!=null){Map<String,Object> task=jdbc.queryForMap("SELECT t.dirty_version,s.shipment_sn FROM oms_order_sales_document_sync t JOIN oms_order_shipment s ON s.company_code=t.company_code AND s.id=t.shipment_id WHERE t.company_code=? AND t.shipment_id=?",company,shipment);long v=((Number)task.get("dirty_version")).longValue();outbox.enqueue(company,"SALES_DOCUMENT",shipment,v,String.valueOf(task.get("shipment_sn")),shipment+":"+v,0);}
        else if(immediate!=null){com.oms.common.task.CommittedWork.afterCommit(()->immediate.getObject().salesChanged(company,shipment));}
    }

    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<OrderAutoAllocationWorker> autoAllocation;
    public void autoAllocationChanged(String company,long order,String cause){
        if(OrderAutoAllocationService.enqueue(jdbc,company,order,cause)&&autoAllocation!=null)
            com.oms.common.task.CommittedWork.afterCommit(()->autoAllocation.getObject().kick(company));
    }
    public com.oms.messaging.MessageOutbox messaging(){return messages==null?null:messages.getIfAvailable();}
    @Transactional(rollbackFor=Exception.class)
    public void pullChanged(String company,long task){com.oms.messaging.MessageOutbox out=messaging();if(out==null)return;Map<String,Object> row=jdbc.queryForMap("SELECT work_version FROM oms_order_pull_task WHERE company_code=? AND id=? FOR UPDATE",company,task);if(!jdbc.queryForList("SELECT id FROM oms_message_outbox WHERE company_code=? AND service_code='order' AND message_type='ORDER_PULL' AND business_id=? AND business_version=? LIMIT 1",company,String.valueOf(task),number(row,"work_version")).isEmpty())return;out.enqueue(company,"ORDER_PULL",task,number(row,"work_version"),"ORDERPULL"+task,task+":"+number(row,"work_version"),0);}
    @Transactional(rollbackFor=Exception.class)
    public void conversionChanged(String company,long id,boolean advance){com.oms.messaging.MessageOutbox out=messaging();if(out==null)return;Map<String,Object> row=jdbc.query("SELECT * FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);long version=number(row,"intake_version");if(advance){jdbc.update("UPDATE oms_platform_order SET intake_version=intake_version+1 WHERE company_code=? AND id=?",company,id);version++;}if(row.get("business_order_id")!=null||number(row,"change_pending")!=0||"EXCEPTION".equals(text(row,"conversion_status")))return;
        List<Map<String,Object>> prior=jdbc.queryForList("SELECT process_state FROM oms_message_outbox WHERE company_code=? AND service_code='order' AND message_type='ORDER_CONVERT' AND business_id=? AND business_version=? ORDER BY id DESC LIMIT 1",company,String.valueOf(id),version);
        if(!prior.isEmpty()&&!Arrays.asList("SUCCESS","FAILED").contains(text(prior.get(0),"process_state")))return;
        if(!advance&&!prior.isEmpty()&&Arrays.asList("SUCCESS","FAILED").contains(text(prior.get(0),"process_state"))){jdbc.update("UPDATE oms_platform_order SET intake_version=intake_version+1 WHERE company_code=? AND id=?",company,id);version++;}
        out.enqueue(company,"ORDER_CONVERT",id,version,text(row,"platform_tid"),id+":"+version,0);
    }
    @Transactional(rollbackFor=Exception.class)
    public void retryPull(String company,long id,String actor){Map<String,Object> task=jdbc.query("SELECT * FROM oms_order_pull_task WHERE company_code=? AND id=?",ROW,company,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("拉单任务不存在"));Map<String,Object> config=jdbc.query("SELECT * FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,task.get("channel_id")).get(0);require(config.get("lease_until")==null||time(text(config,"lease_until")).isBefore(LocalDateTime.now()),"店铺仍在处理，请稍后重试");task=jdbc.query("SELECT * FROM oms_order_pull_task WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);require(Arrays.asList("FAILED","PENDING","RUNNING").contains(text(task,"status")),"任务已完成；部分失败请从逐单结果重试失败项");require(text(task,"pull_source_key").isEmpty()||text(task,"pull_source_key").equals(text(config,"source_key"))&&text(task,"pull_seller_nick").equals(text(config,"seller_nick")),"店铺来源已改变，请重新提交补拉");jdbc.update("UPDATE oms_order_pull_task SET work_version=work_version+1,status='PENDING',attempts=0,next_retry_at=NOW(),error_message='' WHERE company_code=? AND id=?",company,id);if("TID".equals(text(task,"pull_mode")))jdbc.update("UPDATE oms_order_pull_item SET result='PENDING',message='' WHERE company_code=? AND task_id=? AND result='FAILED'",company,id);pullChanged(company,id);}
    public void messageProgress(String company,List<Map<String,Object>> rows,String type){if(messaging()==null||rows.isEmpty())return;List<Object> args=new ArrayList<>(Arrays.asList(company,type));for(Map<String,Object> row:rows)args.add(String.valueOf(row.get("id")));Map<String,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> row:jdbc.query("SELECT m.business_id,m.event_id,m.process_state,m.problem_code,m.last_error,m.business_attempts,m.next_process_at FROM oms_message_outbox m WHERE m.company_code=? AND m.service_code='order' AND m.message_type=? AND m.business_id IN ("+String.join(",",Collections.nCopies(rows.size(),"?"))+") AND NOT EXISTS(SELECT 1 FROM oms_message_outbox newer WHERE newer.company_code=m.company_code AND newer.service_code=m.service_code AND newer.message_type=m.message_type AND newer.business_id=m.business_id AND newer.id>m.id)",ROW,args.toArray()))byId.put(text(row,"business_id"),row);for(Map<String,Object> row:rows)row.put("message",byId.get(String.valueOf(row.get("id"))));}
    public JdbcTemplate db(){return jdbc;}
    public Map<String,Map<String,Object>> bundleSnapshots(String company,long id){Map<String,Map<String,Object>> result=new HashMap<>();for(Map<String,Object> l:jdbc.query("SELECT platform_oid,bundle_snapshot FROM oms_platform_order_line WHERE company_code=? AND platform_order_id=? AND bundle_snapshot IS NOT NULL",ROW,company,id))result.put(text(l,"platform_oid"),OrderBundles.object(l.get("bundle_snapshot")));return result;}
    public Map<String,String> manualMatches(String company,long id){Map<String,String> result=new HashMap<>();for(Map<String,Object> line:jdbc.query("SELECT platform_oid,manual_sku_sn FROM oms_platform_order_line WHERE company_code=? AND platform_order_id=? AND manual_sku_sn IS NOT NULL",ROW,company,id))result.put(text(line,"platform_oid"),text(line,"manual_sku_sn"));return result;}
    public Map<String,String> frozenMatches(String company,long id){Map<String,String> result=new HashMap<>();for(Map<String,Object> row:jdbc.query("SELECT platform_oid,sku_sn FROM oms_platform_order_line WHERE company_code=? AND platform_order_id=? AND demand_prepared=1 AND sku_sn IS NOT NULL",ROW,company,id))result.put(text(row,"platform_oid"),text(row,"sku_sn"));return result;}
    @Transactional(rollbackFor=Exception.class)
    public void bindingSnapshots(String company,long id,String token,Map<String,Map<String,Object>> snapshots){Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);for(Map.Entry<String,Map<String,Object>> e:snapshots.entrySet())jdbc.update("UPDATE oms_platform_order_line SET product_binding_id=?,product_binding_revision=?,product_match_source=? WHERE company_code=? AND platform_order_id=? AND platform_oid=? AND demand_prepared=0",e.getValue().get("binding_id"),e.getValue().get("binding_revision"),e.getValue().get("source"),company,id,e.getKey());}
    /** Least recently attempted first, so repeatedly short large orders cannot monopolize a batch. */
    public List<Map<String,Object>> dueOrders(String company,long channel,boolean convert){return jdbc.query("SELECT p.id FROM oms_platform_order p LEFT JOIN oms_order o ON o.id=p.business_order_id AND o.company_code=p.company_code WHERE p.company_code=? AND p.channel_id=? AND p.source_kind='PLATFORM' AND p.change_pending=0 AND p.next_retry_at<=NOW() AND (p.conversion_status IN ('PENDING','PROCESSING') OR (?=1 AND p.conversion_status='WAITING' AND p.platform_status='WAIT_SELLER_SEND_GOODS' AND JSON_CONTAINS((SELECT c.allowed_trade_types FROM oms_order_pull_config c WHERE c.company_code=p.company_code AND c.channel_id=p.channel_id),JSON_QUOTE(p.trade_type))) OR (p.conversion_status='SUCCESS' AND o.status='WAIT_ALLOCATION' AND o.inventory_status<>'RESERVED' AND o.manual_hold=0 AND p.fulfillment_block_reason='' AND p.platform_status='WAIT_SELLER_SEND_GOODS')) ORDER BY p.next_retry_at,p.id LIMIT 10",ROW,company,channel,convert?1:0);}
    public long insert(String sql,Object...args){GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(connection->{java.sql.PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);return key.getKey().longValue();}
    public Map<String,Object> config(String company,long channel){List<Map<String,Object>> rows=jdbc.query("SELECT * FROM oms_order_pull_config WHERE company_code=? AND channel_id=?",ROW,company,channel);require(!rows.isEmpty(),"店铺尚未配置订单拉取");return rows.get(0);}
    public Map<String,Object> raw(String company,long id){List<Map<String,Object>> rows=jdbc.query("SELECT * FROM oms_platform_order WHERE company_code=? AND id=?",ROW,company,id);require(!rows.isEmpty(),"平台订单不存在或无权访问");return rows.get(0);}
    public void log(String company,long id,String action,String result,String message,String actor){jdbc.update("INSERT INTO oms_order_process_log(company_code,platform_order_id,action,result,message,operator_name) VALUES (?,?,?,?,?,?)",company,id,action,result,message.substring(0,Math.min(500,message.length())),actor);}
    public boolean claim(String company,long channel,String token){return jdbc.update("UPDATE oms_order_pull_config SET lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) WHERE company_code=? AND channel_id=? AND (lease_until IS NULL OR lease_until<NOW())",token,company,channel)==1;}
    public boolean renew(String company,long channel,String token){return jdbc.update("UPDATE oms_order_pull_config SET lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) WHERE company_code=? AND channel_id=? AND lease_token=? AND lease_until>NOW()",company,channel,token)==1;}
    public void release(String company,long channel,String token){jdbc.update("UPDATE oms_order_pull_config SET lease_token=NULL,lease_until=NULL WHERE company_code=? AND channel_id=? AND lease_token=?",company,channel,token);}
    void fence(String company,long channel,String token){List<Map<String,Object>> rows=jdbc.queryForList("SELECT id FROM oms_order_pull_config WHERE company_code=? AND channel_id=? AND lease_token=? AND lease_until>NOW() FOR UPDATE",company,channel,token);require(rows.size()==1,"店铺任务租约已失效，当前任务停止提交");}

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> nextTask(String company,long channel,String token){
        fence(company,channel,token);Map<String,Object> config=config(company,channel);
        if(number(config,"enabled")==1&&!time(text(config,"next_run_at")).isAfter(LocalDateTime.now())){
            long pending=jdbc.queryForObject("SELECT COUNT(*) FROM oms_order_pull_task WHERE company_code=? AND channel_id=? AND task_type='AUTO' AND status IN ('PENDING','RUNNING')",Long.class,company,channel);
            if(pending==0){LocalDateTime end=LocalDateTime.now().minusSeconds(3).withNano(0),start=time(text(config,"cursor_time")).minusMinutes(2),initial=time(text(config,"initial_time"));if(start.isBefore(initial))start=initial;if(end.isAfter(start))newTask(company,channel,"AUTO",start.format(TIME),end.format(TIME),"定时任务");}
            jdbc.update("UPDATE oms_order_pull_config SET next_run_at=DATE_ADD(NOW(),INTERVAL ? SECOND) WHERE company_code=? AND channel_id=?",config.get("interval_seconds"),company,channel);
        }
        List<Map<String,Object>> tasks=jdbc.query("SELECT * FROM oms_order_pull_task WHERE company_code=? AND channel_id=? AND status IN ('PENDING','RUNNING') AND next_retry_at<=NOW() ORDER BY next_retry_at,id LIMIT 1",ROW,company,channel);
        if(tasks.isEmpty())return null;Map<String,Object> task=tasks.get(0);jdbc.update("UPDATE oms_order_pull_task SET status='RUNNING' WHERE id=?",task.get("id"));if("TID".equals(text(task,"pull_mode")))task.put("selected_tids",jdbc.queryForList("SELECT platform_tid FROM oms_order_pull_item WHERE company_code=? AND task_id=? AND result='PENDING' ORDER BY id LIMIT 50",String.class,company,task.get("id")));return task;
    }
    @Transactional(rollbackFor=Exception.class)
    public long newTask(String company,long channel,String type,String start,String end,String actor){long id=insert("INSERT INTO oms_order_pull_task(company_code,channel_id,task_type,start_time,end_time,page_time,operator_name) VALUES (?,?,?,?,?,?,?)",company,channel,type,start,end,start,actor);pullChanged(company,id);return id;}

    @Transactional(rollbackFor=Exception.class)
    public void page(String company,long channel,String token,Map<String,Object> task,List<Map<String,Object>> rows,int limit){new OrderBackfillService(this,null).commitPage(company,channel,token,task,rows,limit);}
    private Map<String,Object> pullResult(String code,String message,long id){Map<String,Object> result=new LinkedHashMap<>();result.put("result",code);result.put("message",message);result.put("platform_order_id",id);return result;}
    Map<String,Object> ingest(String company,long channel,Map<String,Object> row){
        require(text(row,"tid").matches("[1-9][0-9]{0,18}"),"来源平台单号格式错误");
        require(text(row,"seller_nick").equals(text(config(company,channel),"seller_nick")),"来源卖家与所选店铺不一致");
String tid=text(row,"tid"),payload=text(row,"jdp_response"),fingerprint,block;try{TmallOrderParser.Trade parsed=new TmallOrderParser().parse(payload);fingerprint=parsed.fingerprint;block=parsed.fulfillmentBlock;}catch(IllegalArgumentException e){fingerprint=hash(payload);block="平台报文无法解析";}
            List<Map<String,Object>> old=jdbc.query("SELECT * FROM oms_platform_order WHERE company_code=? AND channel_id=? AND platform_tid=? FOR UPDATE",ROW,company,channel,tid);
            if(old.isEmpty()){insert("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,fulfillment_block_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",company,channel,tid,row.get("status"),row.get("type"),row.get("seller_nick"),row.get("created"),row.get("modified"),row.get("jdp_modified"),fingerprint,payload,block);RefundGuard.refresh(this,company,channel,tid);long added=jdbc.queryForObject("SELECT id FROM oms_platform_order WHERE company_code=? AND channel_id=? AND platform_tid=?",Long.class,company,channel,tid);conversionChanged(company,added,false);return pullResult("NEW","已新增平台订单",added);}
            Map<String,Object> result=new OrderUpdateService(this).ingest(company,old.get(0),row);RefundGuard.refresh(this,company,channel,tid);if(!"SKIPPED".equals(text(result,"result")))conversionChanged(company,number(old.get(0),"id"),true);return result;
    }

    @Transactional(rollbackFor=Exception.class)
    public void taskFailed(String company,long channel,String token,long task,String message){fence(company,channel,token);jdbc.update("UPDATE oms_order_pull_task SET status=IF(attempts>=4,'FAILED','PENDING'),attempts=attempts+1,next_retry_at=DATE_ADD(NOW(),INTERVAL 60 SECOND),error_message=? WHERE company_code=? AND id=?",message,company,task);jdbc.update("UPDATE oms_order_pull_item i JOIN oms_order_pull_task t ON t.id=i.task_id AND t.company_code=i.company_code SET i.result='FAILED',i.message=? WHERE i.company_code=? AND i.task_id=? AND i.result='PENDING' AND t.status='FAILED'",message,company,task);jdbc.update("UPDATE oms_order_pull_config SET last_error=? WHERE company_code=? AND channel_id=?",message,company,channel);}

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> snapshot(String company,long id,String token){Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);require(number(order,"change_pending")==0,"订单数据已变化，等待更新处理");if(order.get("business_order_id")!=null){Map<String,Object> business=jdbc.query("SELECT status,manual_hold FROM oms_order WHERE company_code=? AND id=?",ROW,company,order.get("business_order_id")).get(0);if(!"WAIT_ALLOCATION".equals(text(business,"status"))||number(business,"manual_hold")!=0||!OrderReviewPolicy.systemBlock(order).isEmpty())return null;}if(order.get("processing_payload")==null)jdbc.update("UPDATE oms_platform_order SET processing_payload=raw_payload,processing_hash=payload_hash WHERE id=?",id);jdbc.update("UPDATE oms_platform_order SET conversion_status='PROCESSING' WHERE id=? AND business_order_id IS NULL",id);if(order.get("business_order_id")!=null)jdbc.update("UPDATE oms_order SET stock_attempt_status='PROCESSING',stock_attempt_at=NOW() WHERE company_code=? AND id=?",company,order.get("business_order_id"));return raw(company,id);}

    @Transactional(rollbackFor=Exception.class)
    public void parsed(String company,long id,String token,TmallOrderParser.Trade trade,Map<String,String> matched,Map<String,String> errors){
        Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);
        for(TmallOrderParser.Line line:trade.lines)jdbc.update("INSERT INTO oms_platform_order_line(company_code,platform_order_id,platform_oid,platform_item_id,platform_sku_id,outer_sku_id,outer_iid,title,platform_spec,quantity,payment,sku_sn,match_error) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE sku_sn=IF(demand_prepared=1,sku_sn,VALUES(sku_sn)),match_error=VALUES(match_error),payment=VALUES(payment)",company,id,line.oid,line.itemId,line.skuId,line.outerSku,line.outerIid,line.title,line.spec,line.quantity,line.payment,matched.get(line.oid),errors.getOrDefault(line.oid,""));
        jdbc.update("UPDATE oms_platform_order SET payment=?,receiver_name=?,receiver_phone=?,receiver_address=?,oaid=? WHERE id=?",trade.payment,trade.name,trade.phone,trade.address,trade.oaid,id);
    }
    @Transactional(rollbackFor=Exception.class)
    public void bundleSnapshots(String company,long id,String token,Map<String,Map<String,Object>> snapshots){Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);for(Map.Entry<String,Map<String,Object>> entry:snapshots.entrySet())jdbc.update("UPDATE oms_platform_order_line SET bundle_snapshot=COALESCE(bundle_snapshot,?) WHERE company_code=? AND platform_order_id=? AND platform_oid=? AND demand_prepared=0",encode(entry.getValue()),company,id,entry.getKey());}
    @Transactional(rollbackFor=Exception.class)
    public void prepareDemand(String company,long id,String token){Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);jdbc.update("UPDATE oms_platform_order_line SET demand_prepared=1 WHERE company_code=? AND platform_order_id=? AND sku_sn IS NOT NULL",company,id);jdbc.update("UPDATE oms_platform_order SET demand_registered=2 WHERE company_code=? AND id=? AND demand_registered=0",company,id);}
    @Transactional(rollbackFor=Exception.class)
    public void registered(String company,long id,String token){Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);if(number(order,"demand_registered")!=1){jdbc.update("UPDATE oms_platform_order SET demand_registered=1 WHERE id=?",id);log(company,id,"DEMAND_REGISTER","SUCCESS","已匹配商品销售需求已登记","订单任务");}}
    @Transactional(rollbackFor=Exception.class)
    public void state(String company,long id,String token,String state,String reason){Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);jdbc.update("UPDATE oms_platform_order SET conversion_status=?,conversion_error=?,exception_code='',exception_details=NULL,exception_line_count=0,system_failures=0,next_retry_at=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE id=? AND business_order_id IS NULL",state,reason,id);if(order.get("business_order_id")!=null)jdbc.update("UPDATE oms_platform_order SET next_retry_at=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE id=?",id);if(!state.equals(text(order,"conversion_status"))||!reason.equals(text(order,"conversion_error")))log(company,id,"CONVERT",state,reason,"订单任务");}

    /** Five delayed retries after the initial failure. Metadata and queue transition commit together. */
    @Transactional(rollbackFor=Exception.class)
    public void failure(String company,long id,String token,String code,String reason,List<Map<String,Object>> details,boolean transientFailure){failure(company,id,token,code,reason,details,transientFailure,false);}
    @Transactional(rollbackFor=Exception.class)
    public void failure(String company,long id,String token,String code,String reason,List<Map<String,Object>> details,boolean transientFailure,boolean managed){
        Map<String,Object> order=raw(company,id);fence(company,number(order,"channel_id"),token);
        if(order.get("business_order_id")!=null){
            jdbc.update("UPDATE oms_platform_order SET next_retry_at=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE company_code=? AND id=?",company,id);
            String previous=jdbc.queryForObject("SELECT stock_error FROM oms_order WHERE company_code=? AND id=?",String.class,company,order.get("business_order_id"));
            jdbc.update("UPDATE oms_order SET stock_attempt_status='FAILED',stock_error=? WHERE company_code=? AND id=?",reason,company,order.get("business_order_id"));
            if(!reason.equals(previous))log(company,id,"STOCK_RETRY","FAILED",reason,"订单任务");return;
        }
        int failures=transientFailure?(int)number(order,"system_failures")+1:0;
        boolean queued=transientFailure&&(managed||failures<=5);
        int delay=managed?0:transientFailure?new int[]{60,120,300,600,1800}[Math.min(Math.max(failures-1,0),4)]:60;
        String message=reason+(transientFailure&&managed?"；等待消息任务自动重试":transientFailure?(queued?"；等待第"+failures+"/5次自动重试":"；自动重试已达上限，请人工重试"):"");
        jdbc.update("UPDATE oms_platform_order SET conversion_status=?,conversion_error=?,exception_code=?,exception_details=?,exception_line_count=?,first_exception_at=COALESCE(first_exception_at,NOW()),last_exception_at=NOW(),system_failures=?,next_retry_at=DATE_ADD(NOW(),INTERVAL ? SECOND) WHERE company_code=? AND id=?",queued?"PENDING":"EXCEPTION",message.substring(0,Math.min(480,message.length())),code,encode(details),details.stream().filter(d->!text(d,"platform_oid").isEmpty()).count(),failures,delay,company,id);
        log(company,id,"CONVERT",queued?"PENDING":"EXCEPTION",message,"订单任务");
    }

    @Transactional(rollbackFor=Exception.class)
    public void complete(String company,long id,String token,Map<String,Object> config,List<Map<String,Object>> stock){
        Map<String,Object> platform=raw(company,id);long channel=number(platform,"channel_id");fence(company,channel,token);require(number(platform,"change_pending")==0,"订单数据变化，停止转单");
        Map<String,Map<String,Object>> byOid=new HashMap<>();for(Map<String,Object> row:stock)byOid.put((text(row,"stock_reference").isEmpty()?text(platform,"platform_tid"):text(row,"stock_reference"))+"/"+com.oms.common.util.FulfillmentLineKey.of(row),row);
        boolean existing=platform.get("business_order_id")!=null;
        if(existing)require("WAIT_ALLOCATION".equals(jdbc.queryForObject("SELECT status FROM oms_order WHERE company_code=? AND id=?",String.class,company,platform.get("business_order_id"))),"订单已停止履约，不能提交预占结果");
        List<Map<String,Object>> lines=existing?jdbc.query("SELECT * FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type='SALE' ORDER BY id",ROW,company,platform.get("business_order_id")):jdbc.query("SELECT * FROM oms_platform_order_line WHERE company_code=? AND platform_order_id=? ORDER BY id",ROW,company,id);
        if(!existing)lines=OrderBundles.expand(lines);require(!lines.isEmpty()&&byOid.size()==lines.size(),"库存服务返回的订单明细不完整");
        long reserved=0,quantity=0;for(Map<String,Object> line:lines){Map<String,Object> inventory=byOid.get((text(line,"stock_reference").isEmpty()?text(platform,"platform_tid"):text(line,"stock_reference"))+"/"+com.oms.common.util.FulfillmentLineKey.of(line));require(inventory!=null,"库存服务返回的订单明细不完整");require(number(inventory,"reserved_quantity")>=0&&number(inventory,"reserved_quantity")<=number(line,"quantity")-number(line,"cancelled_quantity")&&(number(line,"component_quantity")>0||number(inventory,"reserved_quantity")==0||number(inventory,"reserved_quantity")==number(line,"quantity")-number(line,"cancelled_quantity")),"库存服务返回的预占数量无效");quantity+=number(line,"quantity")-number(line,"cancelled_quantity");reserved+=number(inventory,"reserved_quantity");}
        String state=reserved==quantity?"RESERVED":reserved==0?"WAIT_RESERVE":"PART_RESERVED";long orderId;
        if(platform.get("business_order_id")==null){orderId=insert("INSERT INTO oms_order(company_code,order_sn,channel_id,platform_order_id,platform_tid,inventory_status,payment,receiver_name,receiver_phone,receiver_address,oaid) VALUES (?,?,?,?,?,?,?,?,?,?,?)",company,"SO"+token(),channel,id,platform.get("platform_tid"),state,platform.get("payment"),platform.get("receiver_name"),platform.get("receiver_phone"),platform.get("receiver_address"),platform.get("oaid"));
            for(Map<String,Object> line:lines){Map<String,Object> inventory=byOid.get((text(line,"stock_reference").isEmpty()?text(platform,"platform_tid"):text(line,"stock_reference"))+"/"+com.oms.common.util.FulfillmentLineKey.of(line));jdbc.update("INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,reserved_quantity,inventory_reason) VALUES (?,?,?,?,?,?,?,?,?)",company,orderId,line.get("platform_oid"),line.get("sku_sn"),line.getOrDefault("goods_name",line.get("title")),line.get("quantity"),line.get("payment"),inventory.get("reserved_quantity"),inventory.get("reason"));if(line.get("stocked_bundle_snapshot")!=null)jdbc.update("UPDATE oms_order_line SET stocked_bundle_snapshot=? WHERE company_code=? AND order_id=? AND platform_oid=? AND sku_sn=?",encode(line.get("stocked_bundle_snapshot")),company,orderId,line.get("platform_oid"),line.get("sku_sn"));Long bundle=OrderBundles.persist(this,company,orderId,line);if(bundle!=null)jdbc.update("UPDATE oms_order_line SET bundle_id=?,component_quantity=? WHERE company_code=? AND order_id=? AND platform_oid=? AND sku_sn=?",bundle,line.get("component_quantity"),company,orderId,line.get("platform_oid"),line.get("sku_sn"));}
            jdbc.update("UPDATE oms_platform_order SET business_order_id=?,conversion_status='SUCCESS',conversion_error='',exception_code='',exception_details=NULL,exception_line_count=0,system_failures=0 WHERE id=?",orderId,id);log(company,id,"CONVERT","SUCCESS","已生成业务订单；逻辑预占状态："+("RESERVED".equals(state)?"逻辑预占完成":"PART_RESERVED".equals(state)?"部分逻辑预占":"待逻辑预占"),"订单任务");
        }else{orderId=number(platform,"business_order_id");jdbc.update("UPDATE oms_order SET inventory_status=? WHERE company_code=? AND id=?",state,company,orderId);
            List<String> changes=new ArrayList<>();for(Map<String,Object> line:lines){Map<String,Object> row=byOid.get((text(line,"stock_reference").isEmpty()?text(platform,"platform_tid"):text(line,"stock_reference"))+"/"+com.oms.common.util.FulfillmentLineKey.of(line));if(number(line,"reserved_quantity")!=number(row,"reserved_quantity"))changes.add(text(line,"sku_sn")+"："+number(line,"reserved_quantity")+" → "+number(row,"reserved_quantity")+" 件");}
            if(!changes.isEmpty())log(company,id,"STOCK_PROGRESS","SUCCESS","逻辑预占进度更新："+String.join("；",changes),"订单任务");
            for(Map<String,Object> row:stock)jdbc.update("UPDATE oms_order_line SET reserved_quantity=?,inventory_reason=? WHERE company_code=? AND order_id=? AND platform_oid=? AND sku_sn=? AND business_type='SALE' AND COALESCE(NULLIF(stock_reference,''),?)=?",row.get("reserved_quantity"),row.get("reason"),company,orderId,row.get("platform_oid"),row.get("sku_sn"),platform.get("platform_tid"),text(row,"stock_reference").isEmpty()?text(platform,"platform_tid"):text(row,"stock_reference"));}
        for(Map<String,Object> row:stock)jdbc.update("UPDATE oms_order_line SET inventory_reason_code=? WHERE company_code=? AND order_id=? AND platform_oid=? AND sku_sn=? AND business_type='SALE' AND COALESCE(NULLIF(stock_reference,''),?)=?",text(row,"reason_code"),company,orderId,row.get("platform_oid"),row.get("sku_sn"),platform.get("platform_tid"),text(row,"stock_reference").isEmpty()?text(platform,"platform_tid"):text(row,"stock_reference"));
        jdbc.update("UPDATE oms_order SET stock_attempt_status='DONE',stock_error='',stock_attempt_at=COALESCE(stock_attempt_at,NOW()),reserved_at=IF(?='RESERVED',COALESCE(reserved_at,NOW()),reserved_at) WHERE company_code=? AND id=?",state,company,orderId);
        jdbc.update("UPDATE oms_platform_order SET next_retry_at=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE id=?",id);
        RefundGuard.refresh(this,company,channel,text(platform,"platform_tid"));
        PlatformExchangeGuard.refreshOrder(this,company,orderId);
        long oldReserved=existing?lines.stream().mapToLong(line->number(line,"reserved_quantity")).sum():-1;
        if(!existing||oldReserved!=reserved)OrderAutoReviewService.evaluate(this,company,orderId,false,"系统审单");
        if(oldReserved!=reserved)autoAllocationChanged(company,orderId,"逻辑预占更新");
    }
}
