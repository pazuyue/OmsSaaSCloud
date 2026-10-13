package com.oms.order.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Package notifications reference posted quantities; they never change inventory or warehouse status. */
@Service
public class OrderPlatformShippingService {
    @Resource private OrderRepository repository;
    @Resource private OrderPlatformShippingGateway gateway;
    public OrderPlatformShippingService(){}
    OrderPlatformShippingService(OrderRepository repository){this.repository=repository;}
    private TransactionTemplate tx(){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));}
    private Map<String,Object> shipment(String company,long id,boolean lock){
        List<Map<String,Object>> rows=repository.db().query("SELECT s.*,o.status order_status FROM oms_order_shipment s JOIN oms_order o ON o.company_code=s.company_code AND o.id=s.order_id WHERE s.company_code=? AND s.id=?"+(lock?" FOR UPDATE":""),ROW,company,id);
        require(rows.size()==1,"发货单不存在或无权访问");return rows.get(0);
    }
    private void orderLock(String company,long id){require(repository.db().queryForObject("SELECT id FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",Long.class,company,id)!=null,"订单不存在");}
    public Map<String,Object> configuration(String company,long channel){
        require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_pull_config WHERE company_code=? AND channel_id=?",Long.class,company,channel)==1,"店铺不存在或无权访问");
        List<Map<String,Object>> rows=repository.db().query("SELECT channel_id,mode,carrier_mapping,enabled_at,control_version,updated_by,update_time FROM oms_order_platform_shipping_config WHERE company_code=? AND channel_id=?",ROW,company,channel);
        Map<String,Object> row=rows.isEmpty()?new LinkedHashMap<>():rows.get(0);
        if(rows.isEmpty()){row.put("channel_id",channel);row.put("mode","OFF");row.put("control_version",0);row.put("carrier_mapping","{}");}
        try{row.put("carrier_mapping",JSON.readValue(text(row,"carrier_mapping"),Map.class));}catch(Exception e){throw new IllegalArgumentException("平台快递映射无法解析");}
        return row;
    }
    public Map<String,Object> saveConfiguration(String company,long channel,Map<String,Object> input,String actor){
        require(Arrays.asList("OFF","SIMULATION").contains(text(input,"mode")),"当前仅支持关闭或模拟回传，真实接口尚未启用");
        require(input.containsKey("control_version")&&input.get("carrier_mapping") instanceof Map,"缺少配置版本或快递映射");
        Map<?,?> raw=(Map<?,?>)input.get("carrier_mapping");require(raw.size()<=100,"快递映射最多100项");Map<String,String> mappings=new TreeMap<>();
        for(Map.Entry<?,?> e:raw.entrySet()){require(e.getKey() instanceof String&&e.getValue() instanceof String,"快递编码无效");String a=(String)e.getKey(),b=(String)e.getValue();require(a.matches("[A-Za-z0-9_-]{1,64}")&&b.matches("[A-Za-z0-9_-]{1,64}"),"请填写有效OMS及平台快递编码");mappings.put(a,b);}
        require(!"SIMULATION".equals(text(input,"mode"))||!mappings.isEmpty(),"模拟回传前请配置平台快递编码");
        tx().execute(status->{configuration(company,channel);repository.db().update("INSERT IGNORE INTO oms_order_platform_shipping_config(company_code,channel_id,carrier_mapping) VALUES (?,?,'{}')",company,channel);
            Map<String,Object> old=repository.db().query("SELECT * FROM oms_order_platform_shipping_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel).get(0);
            require(number(old,"control_version")==number(input,"control_version"),"配置已变化，请刷新后重试");
            repository.db().update("UPDATE oms_order_platform_shipping_config SET enabled_at=IF(?='OFF',NULL,IF(mode='OFF',NOW(),enabled_at)),mode=?,carrier_mapping=?,control_version=control_version+1,updated_by=? WHERE company_code=? AND channel_id=?",text(input,"mode"),text(input,"mode"),encode(mappings),actor,company,channel);return null;
        });return configuration(company,channel);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> payload(String company,Map<String,Object> ship,Map<String,Object> pack,Map<String,Object> config){
        require("SIMULATION".equals(text(config,"mode")),"店铺平台回传未开启；当前可在发货单页面配置模拟模式");
        require(Arrays.asList("PART_SHIPPED","SHIPPED").contains(text(ship,"status"))&&!Arrays.asList("VOIDING","VOIDED").contains(text(ship,"order_status")),"须先完成实际出库，作废单不能回传");
        require(text(pack,"express_code").matches("TEST-[A-Za-z0-9_-]{1,95}"),"模拟模式仅接收TEST-测试运单，真实运单请等待正式接口启用");
        require(text(ship,"platform_tid").matches("[0-9]{1,30}"),"平台主单号无效");
        String carrier=text((Map<String,Object>)config.get("carrier_mapping"),text(pack,"carrier_code"));require(!carrier.isEmpty(),"该快递尚未配置平台编码映射");
        require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_outbound WHERE company_code=? AND shipment_id=? AND state='COMPLETED'",Long.class,company,ship.get("id"))>0,"实际出库尚未入账，不能生成回传任务");
        long posted=repository.db().queryForObject("SELECT COALESCE(SUM(quantity),0) FROM oms_order_shipment_outbound WHERE company_code=? AND shipment_id=? AND state='COMPLETED'",Long.class,company,ship.get("id"));
        long shipped=repository.db().queryForObject("SELECT COALESCE(SUM(shipped_quantity),0) FROM oms_order_shipment_line WHERE company_code=? AND shipment_id=?",Long.class,company,ship.get("id"));
        require(posted==shipped&&posted>0,"实际出库数量与入账记录不一致，请先核对出库");
        require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_outbound WHERE company_code=? AND shipment_id=? AND state IN ('PENDING','UNKNOWN','FAILED')",Long.class,company,ship.get("id"))==0,"出库仍有未确认记录，请先核对出库");
        List<Map<String,Object>> source=repository.db().query("SELECT pl.shipment_line_id,pl.platform_oid,pl.sku_sn,pl.quantity,l.platform_oid source_oid,l.sku_sn source_sku,l.order_line_id,l.shipped_quantity,l.shipment_id,(SELECT COALESCE(SUM(x.quantity),0) FROM oms_order_shipment_package_line x WHERE x.company_code=l.company_code AND x.shipment_line_id=l.id) packed,o.quantity ordered_quantity,o.platform_oid order_oid,o.sku_sn order_sku FROM oms_order_shipment_package_line pl LEFT JOIN oms_order_shipment_line l ON l.company_code=pl.company_code AND l.id=pl.shipment_line_id LEFT JOIN oms_order_line o ON o.company_code=l.company_code AND o.id=l.order_line_id AND o.order_id=? WHERE pl.company_code=? AND pl.package_id=? ORDER BY pl.id",ROW,ship.get("order_id"),company,pack.get("id"));
        require(!source.isEmpty(),"包裹商品明细缺失");long total=0;Map<String,Long> goods=new TreeMap<>();
        for(Map<String,Object> line:source){String oid=text(line,"platform_oid"),sku=text(line,"sku_sn");long qty=number(line,"quantity");require(qty>0&&number(line,"shipment_id")==number(ship,"id")&&number(line,"ordered_quantity")>0&&oid.matches("[0-9]{1,30}")&&oid.equals(text(line,"source_oid"))&&oid.equals(text(line,"order_oid"))&&sku.equals(text(line,"source_sku"))&&sku.equals(text(line,"order_sku")),"包裹商品或平台子单与原单不一致");require(number(line,"packed")<=number(line,"shipped_quantity"),"包裹数量超过已入账出库数量");
            long allPacked=repository.db().queryForObject("SELECT COALESCE(SUM(p.quantity),0) FROM oms_order_shipment_package_line p JOIN oms_order_shipment_package h ON h.company_code=p.company_code AND h.id=p.package_id JOIN oms_order_shipment s ON s.company_code=h.company_code AND s.id=h.shipment_id WHERE s.company_code=? AND s.order_id=? AND p.platform_oid=?",Long.class,company,ship.get("order_id"),oid);
            require(allPacked<=number(line,"ordered_quantity"),"累计包裹数量超过平台子单购买数量");total+=qty;goods.put(oid,Math.addExact(goods.getOrDefault(oid,0L),qty));
        }
        require(total==number(pack,"quantity"),"包裹总量与商品明细不一致");
        Map<String,Object> out=new LinkedHashMap<>();out.put("company_code",company);out.put("channel_id",ship.get("channel_id"));out.put("platform_tid",text(ship,"platform_tid"));out.put("shipment_sn",text(ship,"shipment_sn"));out.put("package_code",text(pack,"package_code"));out.put("express_code",text(pack,"express_code"));out.put("carrier_code",text(pack,"carrier_code"));out.put("platform_carrier_code",carrier);out.put("quantity",total);out.put("notification_type","PACKAGE_INCREMENT");out.put("goods",goods);
        List<Map<String,Object>> lines=new ArrayList<>();for(Map<String,Object> line:source){Map<String,Object> clean=new LinkedHashMap<>();for(String field:Arrays.asList("shipment_line_id","platform_oid","sku_sn","quantity"))clean.put(field,line.get(field));lines.add(clean);}out.put("lines",lines);return out;
    }
    public Map<String,Object> report(String company,long id){
        Map<String,Object> ship=shipment(company,id,false),result=new LinkedHashMap<>();result.put("shipment_id",id);result.put("shipment_sn",ship.get("shipment_sn"));Map<String,Object> config=configuration(company,number(ship,"channel_id"));result.put("configuration",config);
        List<Map<String,Object>> tasks=repository.db().query("SELECT t.id,t.package_id,t.request_id,t.mode,t.state,t.quantity,t.attempts,t.query_attempts,t.last_error,t.platform_receipt,t.create_time,t.success_at,t.next_attempt,t.payload_json,p.express_code,p.package_code,p.carrier_name FROM oms_order_platform_shipping_task t JOIN oms_order_shipment_package p ON p.company_code=t.company_code AND p.id=t.package_id WHERE t.company_code=? AND t.shipment_id=? ORDER BY t.id",ROW,company,id);
        for(Map<String,Object> task:tasks){try{task.put("snapshot",JSON.readValue(text(task,"payload_json"),Map.class));}catch(Exception e){task.put("snapshot_error","回传快照无法解析");}task.remove("payload_json");}result.put("tasks",tasks);
        result.put("logs",repository.db().query("SELECT l.task_id,l.action,l.result,l.message,l.operator_name,l.create_time FROM oms_order_platform_shipping_log l JOIN oms_order_platform_shipping_task t ON t.company_code=l.company_code AND t.id=l.task_id WHERE t.company_code=? AND t.shipment_id=? ORDER BY l.id DESC LIMIT 100",ROW,company,id));
        List<Map<String,Object>> packages=repository.db().query("SELECT p.id,p.package_code,p.express_code,p.carrier_code,p.quantity FROM oms_order_shipment_package p WHERE p.company_code=? AND p.shipment_id=? AND NOT EXISTS(SELECT 1 FROM oms_order_platform_shipping_task t WHERE t.company_code=p.company_code AND t.package_id=p.id) ORDER BY p.id",ROW,company,id);
        for(Map<String,Object> pack:packages)try{pack.put("snapshot",payload(company,ship,pack,config));pack.put("eligible",true);}catch(IllegalArgumentException e){pack.put("eligible",false);pack.put("reason",e.getMessage());}result.put("unsubmitted_packages",packages);
        return result;
    }
    /** Explicit enrollment may include the retained acceptance document; automatic scan never backfills old packages. */
    public Map<String,Object> enqueue(String company,long id,String actor){return enqueue(company,id,actor,null);}
    private Map<String,Object> enqueue(String company,long id,String actor,String from){
        Map<String,Object> head=shipment(company,id,false);tx().execute(status->{orderLock(company,number(head,"order_id"));Map<String,Object> ship=shipment(company,id,true);Map<String,Object> config=configuration(company,number(ship,"channel_id"));
            List<Object> args=new ArrayList<>(Arrays.asList(company,id));if(from!=null)args.add(from);
            List<Map<String,Object>> packs=repository.db().query("SELECT * FROM oms_order_shipment_package p WHERE company_code=? AND shipment_id=?"+(from==null?"":" AND create_time>=?")+" AND NOT EXISTS(SELECT 1 FROM oms_order_platform_shipping_task t WHERE t.company_code=p.company_code AND t.package_id=p.id) ORDER BY id FOR UPDATE",ROW,args.toArray());
            List<Map<String,Object>> snapshots=new ArrayList<>();for(Map<String,Object> pack:packs)snapshots.add(payload(company,ship,pack,config));
            for(int n=0;n<packs.size();n++){Map<String,Object> pack=packs.get(n),snapshot=snapshots.get(n);String json=encode(snapshot),request="PS"+hash(company+"/"+pack.get("id")+"/"+text(pack,"payload_hash")).substring(0,40);
                repository.db().update("INSERT INTO oms_order_platform_shipping_task(company_code,channel_id,order_id,shipment_id,package_id,request_id,mode,quantity,payload_json,payload_hash,created_by) VALUES (?,?,?,?,?,?,'SIMULATION',?,?,?,?)",company,ship.get("channel_id"),ship.get("order_id"),id,pack.get("id"),request,pack.get("quantity"),json,hash(json),actor);
                long task=repository.db().queryForObject("SELECT id FROM oms_order_platform_shipping_task WHERE company_code=? AND request_id=?",Long.class,company,request);log(company,task,"ENQUEUE","PENDING","已按实际包裹生成模拟回传任务，未向天猫发送",actor);
            }return null;
        });return report(company,id);
    }
    private void log(String company,long task,String action,String state,String message,String actor){repository.db().update("INSERT INTO oms_order_platform_shipping_log(company_code,task_id,action,result,message,operator_name) VALUES (?,?,?,?,?,?)",company,task,action,state,message,actor);}
    private Map<String,Object> task(String company,long id){List<Map<String,Object>> rows=repository.db().query("SELECT * FROM oms_order_platform_shipping_task WHERE company_code=? AND id=?",ROW,company,id);require(rows.size()==1,"回传任务不存在或无权访问");return rows.get(0);}
    public void action(String company,long shipment,long id,String action,String actor){
        require(Arrays.asList("RETRY","QUERY").contains(action),"回传操作无效");Map<String,Object> head=task(company,id);require(number(head,"shipment_id")==shipment,"任务不属于当前发货单");
        tx().execute(status->{orderLock(company,number(head,"order_id"));Map<String,Object> row=repository.db().query("SELECT * FROM oms_order_platform_shipping_task WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
            if("SUCCESS".equals(text(row,"state")))return null;
            require("RETRY".equals(action)?"FAILED".equals(text(row,"state")):"UNKNOWN".equals(text(row,"state")),"结果待确认时只能核对，明确失败后才可重试");
            if("RETRY".equals(action)){require("SIMULATION".equals(text(configuration(company,number(row,"channel_id")),"mode")),"店铺回传已关闭");repository.db().update("UPDATE oms_order_platform_shipping_task SET state='PENDING',cycle_attempts=0,last_error='',next_attempt=NOW() WHERE company_code=? AND id=?",company,id);}
            else repository.db().update("UPDATE oms_order_platform_shipping_task SET next_attempt=NOW() WHERE company_code=? AND id=?",company,id);
            log(company,id,action,text(row,"state"),"RETRY".equals(action)?"按原请求和商品快照重试模拟回传":"请求核对原模拟回执，不重新提交",actor);syncIssues(company,number(row,"order_id"));return null;
        });
    }
    public void process(String company,long id){
        Map<String,Object> claimed=tx().execute(status->{Map<String,Object> row=task(company,id);
            if("PROCESSING".equals(text(row,"state"))){repository.db().update("UPDATE oms_order_platform_shipping_task SET state='UNKNOWN',last_error='处理超时，先核对原回执',next_attempt=NOW(),lease_token='' WHERE company_code=? AND id=? AND state='PROCESSING' AND lease_until<NOW()",company,id);row=task(company,id);}
            if(!Arrays.asList("PENDING","UNKNOWN").contains(text(row,"state")))return null;
            boolean query="UNKNOWN".equals(text(row,"state"));
            if(!query&&!"SIMULATION".equals(text(configuration(company,number(row,"channel_id")),"mode")))return null;
            String lease=token();int changed=repository.db().update("UPDATE oms_order_platform_shipping_task SET state='PROCESSING',lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 60 SECOND),attempts=attempts+?,cycle_attempts=cycle_attempts+?,query_attempts=query_attempts+? WHERE company_code=? AND id=? AND state=? AND next_attempt<=NOW()",lease,query?0:1,query?0:1,query?1:0,company,id,row.get("state"));
            if(changed==0)return null;Map<String,Object> next=task(company,id);next.put("query_only",query);return next;
        });if(claimed==null)return;
        boolean query=Boolean.TRUE.equals(claimed.get("query_only"));Map<String,Object> reply;
        try{require(hash(text(claimed,"payload_json")).equals(text(claimed,"payload_hash")),"回传快照校验失败");reply=query?gateway.query(claimed):gateway.send(claimed);require(reply!=null&&Arrays.asList("SUCCESS","FAILED","RETRYABLE","UNKNOWN").contains(text(reply,"state")),"回传结果无法确认");}
        catch(Exception e){reply=OrderPlatformShippingGateway.result("UNKNOWN","模拟回传结果未确认，请核对原回执","");}
        if("SUCCESS".equals(text(reply,"state"))&&!text(reply,"receipt").matches("SIMSHIP_[A-Za-z0-9_-]{1,80}"))reply=OrderPlatformShippingGateway.result("UNKNOWN","模拟平台成功结果缺少有效回执，请核对原回执","");
        Map<String,Object> outcome=reply;
        tx().execute(status->{orderLock(company,number(claimed,"order_id"));Map<String,Object> row=repository.db().query("SELECT * FROM oms_order_platform_shipping_task WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);if(!text(row,"lease_token").equals(text(claimed,"lease_token"))||!"PROCESSING".equals(text(row,"state")))return null;
            String state=text(outcome,"state");if("RETRYABLE".equals(state))state=!query&&number(row,"cycle_attempts")<3?"PENDING":"FAILED";if(query&&"FAILED".equals(state))state="UNKNOWN";
            String message=text(outcome,"message");if(message.length()>500)message=message.substring(0,500);
            repository.db().update("UPDATE oms_order_platform_shipping_task SET state=?,last_error=?,platform_receipt=?,success_at=IF(?='SUCCESS',NOW(),NULL),lease_token='',lease_until=NULL,next_attempt=DATE_ADD(NOW(),INTERVAL 30 SECOND) WHERE company_code=? AND id=?",state,"SUCCESS".equals(state)?"":message,text(outcome,"receipt"),state,company,id);
            log(company,id,query?"QUERY":"SEND",state,message,"平台模拟回传任务");syncIssues(company,number(row,"order_id"));return null;
        });
    }
    private void syncIssues(String company,long order){
        List<Map<String,Object>> failed=repository.db().query("SELECT id,shipment_id,package_id,state,quantity,last_error FROM oms_order_platform_shipping_task WHERE company_code=? AND order_id=? AND state<>'SUCCESS' AND (state IN ('FAILED','UNKNOWN') OR EXISTS(SELECT 1 FROM oms_order_platform_shipping_log l WHERE l.company_code=oms_order_platform_shipping_task.company_code AND l.task_id=oms_order_platform_shipping_task.id AND l.result IN ('FAILED','UNKNOWN'))) ORDER BY id",ROW,company,order);List<Map<String,Object>> desired=new ArrayList<>();
        if(!failed.isEmpty()){Map<String,Object> issue=new LinkedHashMap<>();issue.put("domain","PLATFORM_SHIPPING");issue.put("category","PLATFORM");issue.put("reason_code","PLATFORM_SHIPPING_UNCONFIRMED");issue.put("scope","CHANNEL");issue.put("stage","PLATFORM_SHIP");issue.put("sku_sn","");issue.put("severity","WARNING");issue.put("blocking",0);issue.put("message","有 "+failed.size()+" 个包裹模拟平台回传异常尚未确认完成；仓库出库和库存入账已完成，不重复扣库存");Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("basis","PLATFORM_SHIPPING_TASK");evidence.put("tasks",failed);issue.put("evidence",evidence);desired.add(issue);}
        new OrderIssueService(repository).reconcileDomain(company,order,"PLATFORM_SHIPPING",desired);
    }
    private final Map<String,Long> cursors=new HashMap<>();
    public void pollCompany(String company){
        for(Map<String,Object> config:repository.db().query("SELECT channel_id,enabled_at FROM oms_order_platform_shipping_config WHERE company_code=? AND mode='SIMULATION'",ROW,company)){
            String key=String.valueOf(com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder.peek())+"/"+company+"/"+config.get("channel_id");long cursor=cursors.getOrDefault(key,0L);
            List<Long> ids=repository.db().queryForList("SELECT DISTINCT s.id FROM oms_order_shipment s JOIN oms_order_shipment_package p ON p.company_code=s.company_code AND p.shipment_id=s.id WHERE s.company_code=? AND s.channel_id=? AND p.create_time>=? AND s.id>? AND p.express_code LIKE 'TEST-%' AND NOT EXISTS(SELECT 1 FROM oms_order_platform_shipping_task t WHERE t.company_code=p.company_code AND t.package_id=p.id) ORDER BY s.id LIMIT 30",Long.class,company,config.get("channel_id"),config.get("enabled_at"),cursor);
            cursors.put(key,ids.isEmpty()?0L:ids.get(ids.size()-1));for(long id:ids)try{enqueue(company,id,"平台模拟回传任务",text(config,"enabled_at"));}catch(IllegalArgumentException e){/* Not ready: report exposes specific prerequisite; next pass can reassess. */}
        }
        for(long id:repository.db().queryForList("SELECT id FROM oms_order_platform_shipping_task WHERE company_code=? AND ((state IN ('PENDING','UNKNOWN') AND next_attempt<=NOW()) OR (state='PROCESSING' AND lease_until<NOW())) ORDER BY id LIMIT 50",Long.class,company))process(company,id);
    }
    public void attach(String company,List<Map<String,Object>> shipments){
        if(shipments.isEmpty())return;List<Object> args=new ArrayList<>();args.add(company);for(Map<String,Object> row:shipments)args.add(row.get("id"));Map<Long,List<Map<String,Object>>> byShipment=new HashMap<>();
        for(Map<String,Object> task:repository.db().query("SELECT shipment_id,mode,state,COUNT(*) packages,SUM(quantity) quantity FROM oms_order_platform_shipping_task WHERE company_code=? AND shipment_id IN ("+String.join(",",Collections.nCopies(shipments.size(),"?"))+") GROUP BY shipment_id,mode,state",ROW,args.toArray()))byShipment.computeIfAbsent(number(task,"shipment_id"),k->new ArrayList<>()).add(task);
        for(Map<String,Object> row:shipments){List<Map<String,Object>> stats=byShipment.getOrDefault(number(row,"id"),Collections.emptyList());long success=0,pending=0,processing=0,failed=0,unknown=0;for(Map<String,Object> stat:stats){switch(text(stat,"state")){case "SUCCESS":success+=number(stat,"quantity");break;case "FAILED":failed+=number(stat,"packages");break;case "UNKNOWN":unknown+=number(stat,"packages");break;case "PROCESSING":processing+=number(stat,"packages");break;default:pending+=number(stat,"packages");}}
            row.put("platform_shipping_mode",stats.isEmpty()?"OFF":"SIMULATION");row.put("platform_shipping_state",unknown>0?"UNKNOWN":failed>0?"FAILED":processing>0?"PROCESSING":pending>0?"PENDING":success>0?(success<number(row,"packaged_quantity")?"PART_SUCCESS":"SUCCESS"):"NONE");row.put("platform_shipping_success_quantity",success);row.put("platform_shipping_failed_packages",failed);row.put("platform_shipping_unknown_packages",unknown);
        }
    }
}
