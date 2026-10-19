package com.oms.channel.platform;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.channel.platform.ChannelPlatformService.map;
import static com.oms.channel.platform.PlatformInteractionLog.*;
@Service public class ChannelSettings {
 @Resource JdbcTemplate db;
 public Map<String,Object> options(String c){return map("shops",db.queryForList("SELECT channel_id,channel_name,enabled,to_channel_enabled,sync_enabled FROM t_channel WHERE UPPER(company_code)=UPPER(?) AND channel_type='TM' ORDER BY enabled DESC,channel_id",c));}
 public Map<String,Object> shop(String c,long id){List<Map<String,Object>> rows=db.queryForList("SELECT channel_id,channel_name,enabled,to_channel_enabled,sync_enabled FROM t_channel WHERE UPPER(company_code)=UPPER(?) AND channel_id=? AND channel_type='TM'",c,id);require(rows.size()==1,"店铺不存在或不属于当前公司");return rows.get(0);}
 public Map<String,Object> products(String c,long id){shop(c,id);db.update("INSERT IGNORE INTO channel_product_policy(company_code,channel_id) VALUES (?,?)",c,id);Map<String,Object> p=StockSyncService.dates(db.queryForMap("SELECT * FROM channel_product_policy WHERE company_code=? AND channel_id=?",c,id));p.put("shop",shop(c,id));p.put("running",db.queryForList("SELECT id,mode,phase,state,create_time FROM channel_product_pull WHERE company_code=? AND channel_id=? AND state IN ('PENDING','RUNNING') ORDER BY id DESC LIMIT 1",c,id));return p;}
 @Transactional public Map<String,Object> saveProducts(String c,long id,Map<String,Object> body,String actor){products(c,id);long pull=StockSyncService.value(body,"auto_pull",0,1),bind=StockSyncService.value(body,"auto_bind",0,1),retry=StockSyncService.value(body,"rematch_enabled",0,1),minutes=StockSyncService.value(body,"interval_minutes",5,1440);require(db.update("UPDATE channel_product_policy SET auto_pull=?,auto_bind=?,rematch_enabled=?,interval_minutes=?,revision=revision+1,next_run_at=NOW(),updated_by=? WHERE company_code=? AND channel_id=? AND revision=?",pull,bind,retry,minutes,actor,c,id,StockSyncService.value(body,"revision",1,Long.MAX_VALUE))==1,"配置已变化，请刷新后保存");return products(c,id);}
 @Transactional public Map<String,Object> switches(String c,long id,Map<String,Object> body){shop(c,id);db.update("UPDATE t_channel SET to_channel_enabled=?,sync_enabled=? WHERE UPPER(company_code)=UPPER(?) AND channel_id=?",StockSyncService.value(body,"to_channel_enabled",0,1),StockSyncService.value(body,"sync_enabled",0,1),c,id);return shop(c,id);}
 public Map<String,Object> retention(String c){List<Map<String,Object>> rows=db.queryForList("SELECT * FROM channel_company_policy WHERE company_code=?",c);return rows.isEmpty()?map("summary_days",90,"payload_days",14,"revision",0):rows.get(0);}
 @Transactional public Map<String,Object> saveRetention(String c,Map<String,Object> body,String actor){long summaries=StockSyncService.value(body,"summary_days",1,3650),payloads=StockSyncService.value(body,"payload_days",1,3650);require(payloads<=summaries,"报文保留期不能超过摘要保留期");db.update("INSERT IGNORE INTO channel_company_policy(company_code,revision) VALUES (?,0)",c);require(db.update("UPDATE channel_company_policy SET summary_days=?,payload_days=?,revision=revision+1,updated_by=? WHERE company_code=? AND revision=?",summaries,payloads,actor,c,StockSyncService.value(body,"revision",0,Long.MAX_VALUE))==1,"配置已变化，请刷新");return retention(c);}
}
