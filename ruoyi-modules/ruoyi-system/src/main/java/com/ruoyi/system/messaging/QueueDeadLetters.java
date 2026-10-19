package com.ruoyi.system.messaging;

import com.ruoyi.common.core.messaging.QueueKind;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.ruoyi.system.messaging.QueueControlStore.*;

/** Durable quarantine metadata, not a replay queue. Bodies and broker credentials are never stored. */
@Service public class QueueDeadLetters {
 @Resource JdbcTemplate db;
 public void report(Map<String,Object> r){QueueKind k=QueueKind.of(s(r,"business_type"));require(k.service.equals(s(r,"service")),"死信服务不匹配");String fingerprint=s(r,"fingerprint"),company=s(r,"company_code"),event=s(r,"event_id"),state=s(r,"business_state"),reason=s(r,"death_reason");boolean verified=Boolean.TRUE.equals(r.get("verified"));
  require(fingerprint.matches("[a-f0-9]{64}")&&Arrays.asList("rejected","expired","maxlen","delivery_limit","UNVERIFIED_SOURCE","INVALID_ENVELOPE").contains(reason),"死信凭据无效");
  require(Arrays.asList("UNKNOWN","PENDING","WAITING","PROCESSING","SUCCESS","FAILED","UNAVAILABLE").contains(state),"业务核对状态无效");
  require(verified?company.matches("[A-Z0-9_]{1,64}")&&event.matches("ME[a-f0-9]{32}"):company.isEmpty()&&event.isEmpty()&&state.equals("UNKNOWN"),"死信身份必须经过原业务通知校验");
  String number=s(r,"business_number"),latest=s(r,"latest_event_id");require(number.length()<=128&&(!verified?number.isEmpty()&&latest.isEmpty():latest.matches("ME[a-f0-9]{32}")),"死信业务范围无效");
  db.update("INSERT INTO sys_mq_dead_letter(fingerprint,business_type,service_code,company_code,event_id,business_number,verified,death_reason,business_state,latest_event_id,checked_at) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(3)) ON DUPLICATE KEY UPDATE business_state=IF(company_code=VALUES(company_code) AND event_id=VALUES(event_id) AND verified=VALUES(verified),VALUES(business_state),business_state),latest_event_id=IF(company_code=VALUES(company_code) AND event_id=VALUES(event_id) AND verified=VALUES(verified),VALUES(latest_event_id),latest_event_id),checked_at=NOW(3)",fingerprint,k.name(),k.service,company,event,number,verified,reason,state,latest);
 }
 public List<Map<String,Object>> checks(String service){QueueKind.service(service);return db.queryForList("SELECT * FROM sys_mq_dead_letter WHERE service_code=? AND verified=1 AND business_state<>'SUCCESS' ORDER BY checked_at,id LIMIT 20",service);}
 public Map<String,Object> list(String type,int page,int size){QueueKind.of(type);require(page>0&&page<=100000&&size>0&&size<=100,"死信查询范围无效");List<Map<String,Object>> rows=db.queryForList("SELECT * FROM sys_mq_dead_letter WHERE business_type=? ORDER BY id DESC LIMIT ? OFFSET ?",type,size,(page-1)*size);rows.forEach(QueueControlStore::dates);Map<String,Object> result=new LinkedHashMap<>();result.put("rows",rows);result.put("total",db.queryForObject("SELECT COUNT(*) FROM sys_mq_dead_letter WHERE business_type=?",Long.class,type));return result;}
 public List<Map<String,Object>> reviews(long id){List<Map<String,Object>> rows=db.queryForList("SELECT actor,reason,create_time FROM sys_mq_dead_review WHERE dead_id=? ORDER BY id DESC LIMIT 50",id);rows.forEach(QueueControlStore::dates);return rows;}
 public void review(long id,String reason,String actor){require(id>0&&!reason.trim().isEmpty()&&reason.length()<=255,"请填写255字以内的核查依据");new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource())).execute(t->{require(db.update("UPDATE sys_mq_dead_letter SET review_reason=?,reviewed_by=?,reviewed_at=NOW(3) WHERE id=?",reason.trim(),actor,id)==1,"死信记录不存在");db.update("INSERT INTO sys_mq_dead_review(dead_id,actor,reason) VALUES (?,?,?)",id,actor,reason.trim());return null;});}
}
