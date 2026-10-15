package com.oms.order.service;

import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Durable, bounded automatic allocation. A task lease does not replace the parent-order locks. */
@Service
public class OrderAutoAllocationService {
    @Resource private OrderRepository repository;
    @Resource private OrderAllocationService allocation;
    @Resource private OrderWarehouseClient inventory;
    private static final String JOIN=" FROM oms_order o JOIN oms_platform_order p ON p.company_code=o.company_code AND p.id=o.platform_order_id LEFT JOIN oms_order_allocation_config ac ON ac.company_code=o.company_code AND ac.channel_id=o.channel_id";
    private static final String AUTO="COALESCE(JSON_UNQUOTE(JSON_EXTRACT(ac.policy_json,'$.execution_mode')),'MANUAL')='AUTO' AND COALESCE(JSON_EXTRACT(ac.policy_json,'$.enabled'),0)=1";
    static boolean enqueue(JdbcTemplate db,String company,long id,String cause){
        return db.update("INSERT INTO oms_order_auto_allocation(company_code,order_id,trigger_name,next_attempt) SELECT o.company_code,o.id,?,NOW()"+JOIN+" WHERE o.company_code=? AND o.id=? AND o.status='WAIT_ALLOCATION' AND o.review_status='APPROVED' AND o.allocation_status<>'FULL' AND "+AUTO+" AND (o.allocation_quantity=0 OR COALESCE(JSON_EXTRACT(ac.policy_json,'$.auto_replenish'),0)=1) ON DUPLICATE KEY UPDATE trigger_name=VALUES(trigger_name),next_attempt=IF(paused=0 AND state IN ('WAIT_STOCK','WAIT_CONDITION','PENDING','PARTIAL_PAUSED'),NOW(),next_attempt),state=IF(paused=0 AND state IN ('WAIT_STOCK','WAIT_CONDITION','PENDING','PARTIAL_PAUSED'),'PENDING',state)",cause,company,id)>0;
    }
    static void configurationChanged(JdbcTemplate db,String company,long channel){
        db.update("UPDATE oms_order_auto_allocation t JOIN oms_order o ON o.company_code=t.company_code AND o.id=t.order_id SET t.state='PENDING',t.next_attempt=NOW(),t.failures=0,t.trigger_name='店铺规则更新' WHERE t.company_code=? AND o.channel_id=? AND t.state NOT IN ('PROCESSING','DONE','CANCELED') AND t.paused=0",company,channel);
    }
    static void guard(JdbcTemplate db,String company,Map<String,Object> order,String lease){
        List<Map<String,Object>> tasks=db.queryForList("SELECT * FROM oms_order_auto_allocation WHERE company_code=? AND order_id=? AND lease_token=? AND state='PROCESSING' AND paused=0 AND lease_until>NOW()",company,order.get("id"),lease);
        require(tasks.size()==1,"自动分仓已暂停或执行权已变化");
        List<Map<String,Object>> configs=db.queryForList("SELECT policy_json FROM oms_order_allocation_config WHERE company_code=? AND channel_id=?",company,order.get("channel_id"));
        Map<String,Object> policy=configs.isEmpty()?Collections.emptyMap():decode(configs.get(0).get("policy_json"));
        require("AUTO".equals(text(policy,"execution_mode"))&&number(policy,"enabled")==1,"店铺自动分仓已关闭");
        require(number(order,"allocation_quantity")==0||number(policy,"auto_replenish")==1||Arrays.asList("UNCERTAIN","PROCESSING").contains(text(order,"allocation_status")),"本单余量未开启自动补充分仓");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> decode(Object value){try{return JSON.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new IllegalArgumentException("分仓规则无效，请重新保存");}}
    private Map<String,Object> order(String company,long id){List<Map<String,Object>> found=repository.db().queryForList("SELECT o.*,p.change_pending,p.platform_status,p.fulfillment_block_reason,p.conversion_status,p.business_order_id,ac.policy_json current_policy,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(ac.policy_json,'$.execution_mode')),'MANUAL') auto_execution_mode,COALESCE(JSON_EXTRACT(ac.policy_json,'$.enabled'),0) auto_enabled,COALESCE(JSON_EXTRACT(ac.policy_json,'$.auto_replenish'),0) auto_replenish,(SELECT lease_until FROM oms_order_pull_config pc WHERE pc.company_code=o.company_code AND pc.channel_id=o.channel_id) shop_lease_until,COALESCE((SELECT SUM(l.reserved_quantity) FROM oms_order_line l WHERE l.company_code=o.company_code AND l.order_id=o.id),0) reserved_total,COALESCE((SELECT SUM(l.quantity) FROM oms_order_line l WHERE l.company_code=o.company_code AND l.order_id=o.id),0) required_total"+JOIN+" WHERE o.company_code=? AND o.id=?",company,id);require(found.size()==1,"业务订单不存在或无权访问");return found.get(0);}
    public List<Map<String,Object>> due(String company){return repository.db().queryForList("SELECT t.order_id,o.order_sn FROM oms_order_auto_allocation t JOIN oms_order o ON o.company_code=t.company_code AND o.id=t.order_id WHERE t.company_code=? AND t.paused=0 AND t.state IN ('PENDING','WAIT_STOCK','WAIT_CONDITION','RETRY','UNCERTAIN','PROCESSING') AND t.next_attempt<=NOW() AND (t.lease_until IS NULL OR t.lease_until<NOW()) ORDER BY t.next_attempt,t.create_time,t.order_id LIMIT 10",company);}
    private void finish(String company,long id,String lease,String state,String reason,int seconds,int failures){
        if(reason==null)reason="自动分仓暂未完成";if(reason.length()>480)reason=reason.substring(0,480);
        String previous=repository.db().queryForObject("SELECT reason FROM oms_order_auto_allocation WHERE company_code=? AND order_id=?",String.class,company,id);
        int finished=repository.db().update("UPDATE oms_order_auto_allocation SET state=?,reason=?,failures=?,next_attempt=IF(?<0,NULL,DATE_ADD(NOW(),INTERVAL ? SECOND)),lease_token='',lease_until=NULL WHERE company_code=? AND order_id=? AND lease_token=?",state,reason,failures,seconds,Math.max(0,seconds),company,id,lease);
        if(finished==0||reason.equals(previous))return;
        Map<String,Object> o=order(company,id);repository.log(company,number(o,"platform_order_id"),"AUTO_ALLOCATE",state,reason,"系统自动分仓");
    }
    private void fault(String company,long id,String lease,Exception e){
        int failures=repository.db().queryForObject("SELECT failures FROM oms_order_auto_allocation WHERE company_code=? AND order_id=?",Integer.class,company,id)+1;
        boolean uncertain=Arrays.asList("UNCERTAIN","PROCESSING").contains(text(order(company,id),"allocation_status"));
        finish(company,id,lease,failures>=5?"NEEDS_ACTION":uncertain?"UNCERTAIN":"RETRY",failures>=5?"系统故障重试已达5次，请人工核对后重试":uncertain?"分仓结果待确认；下次先核对原库存回执，不直接重复占用":"服务暂不可用，已保留待办并按递增间隔重试",failures>=5?-1:new int[]{30,60,120,300,600}[Math.min(failures-1,4)],failures);
    }
    public void process(String company,long id){
        JdbcTemplate db=repository.db();String lease=token();
        if(db.update("UPDATE oms_order_auto_allocation SET state='PROCESSING',lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND),next_attempt=DATE_ADD(NOW(),INTERVAL 180 SECOND),attempts=attempts+1,last_attempt=NOW() WHERE company_code=? AND order_id=? AND paused=0 AND state IN ('PENDING','WAIT_STOCK','WAIT_CONDITION','RETRY','UNCERTAIN','PROCESSING') AND next_attempt<=NOW() AND (lease_until IS NULL OR lease_until<NOW())",lease,company,id)!=1)return;
        try{
            Map<String,Object> o=order(company,id),policy=decode(o.get("current_policy"));
            if(!"WAIT_ALLOCATION".equals(text(o,"status"))){finish(company,id,lease,"CANCELED","订单已停止履约",-1,0);return;}
            if("FULL".equals(text(o,"allocation_status"))){finish(company,id,lease,"DONE","订单已全部分仓",-1,0);return;}
            if(!"AUTO".equals(text(policy,"execution_mode"))||number(policy,"enabled")!=1){finish(company,id,lease,"DISABLED","店铺自动分仓已关闭",-1,0);return;}
            if(o.get("shop_lease_until")!=null&&time(text(o,"shop_lease_until")).isAfter(java.time.LocalDateTime.now())){finish(company,id,lease,"WAIT_CONDITION","店铺预占任务处理中，等待完成后检查",10,0);return;}
            String block=OrderReviewPolicy.systemBlock(o);
            if(number(o,"manual_hold")==1||!"APPROVED".equals(text(o,"review_status"))||!block.isEmpty()||number(o,"business_order_id")!=id||!"SUCCESS".equals(text(o,"conversion_status"))){finish(company,id,lease,"WAIT_CONDITION",number(o,"manual_hold")==1?"订单已人工挂起":!"APPROVED".equals(text(o,"review_status"))?"等待审核通过":block.isEmpty()?"订单关联状态待确认":block,60,0);return;}
            if(Arrays.asList("UNCERTAIN","PROCESSING").contains(text(o,"allocation_status"))){
                allocation.recoverAutomatic(company,id,lease);o=order(company,id);
                if("FULL".equals(text(o,"allocation_status"))){finish(company,id,lease,"DONE","已核对原分仓回执并恢复完整发货单",-1,0);return;}
            }
            if(number(o,"allocation_quantity")>0&&number(policy,"auto_replenish")!=1){finish(company,id,lease,"PARTIAL_PAUSED","已有部分分仓，未开启自动补充分仓；可人工预览余量",-1,0);return;}
            Map<String,Object> frozen=text(o,"allocation_policy").isEmpty()?policy:decode(o.get("allocation_policy"));
            if(number(o,"reserved_total")<=number(o,"allocation_quantity")||(number(frozen,"allow_partial")==0&&number(o,"reserved_total")<number(o,"required_total"))){finish(company,id,lease,"WAIT_STOCK","等待逻辑预占补足可分仓需求",300,0);return;}
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);item.put("control_version",o.get("control_version"));Map<String,Object> input=new LinkedHashMap<>();input.put("items",Collections.singletonList(item));
            Map<String,Object> preview=allocation.preview(company,input).get(0);
            if(!Boolean.TRUE.equals(preview.get("can_allocate"))){String kind=text(preview,"retry_kind");if("SYSTEM".equals(kind)){fault(company,id,lease,new IllegalStateException());return;}if(text(preview,"message").contains("店铺正在处理")){finish(company,id,lease,"WAIT_CONDITION","店铺正在处理，等待完成后检查",10,0);return;}finish(company,id,lease,"STOCK".equals(kind)?"WAIT_STOCK":"NEEDS_ACTION",text(preview,"message"),"STOCK".equals(kind)?300:-1,0);return;}
            item.put("plan_fingerprint",preview.get("plan_fingerprint"));item.put("policy_fingerprint",preview.get("policy_fingerprint"));
            Map<String,Object> result=allocation.allocateAutomatic(company,input,lease).get(0);
            if(!Boolean.TRUE.equals(result.get("success"))){
                Map<String,Object> current=order(company,id);
                if(number(current,"control_version")!=number(o,"control_version")||"FULL".equals(text(current,"allocation_status"))){finish(company,id,lease,"FULL".equals(text(current,"allocation_status"))?"DONE":"WAIT_CONDITION","订单已由其他操作更新，重新检查后继续", "FULL".equals(text(current,"allocation_status"))?-1:5,0);return;}
                if("SYSTEM".equals(text(result,"retry_kind"))){fault(company,id,lease,new IllegalStateException());return;}
                // A competing stock/rule change invalidates this attempt. Re-preview only; never force the stale plan.
                boolean changed="CHANGED".equals(text(result,"retry_kind"));finish(company,id,lease,changed?"RETRY":"NEEDS_ACTION",text(result,"message"),changed?5:-1,0);return;
            }
            o=order(company,id);boolean full="FULL".equals(text(o,"allocation_status"));
            finish(company,id,lease,full?"DONE":number(policy,"auto_replenish")==1?"WAIT_STOCK":"PARTIAL_PAUSED",full?"自动分仓完成，发货单待后续处理":number(policy,"auto_replenish")==1?"已部分分仓，余量等待补货后继续":"已部分分仓，余量等待人工处理",full||number(policy,"auto_replenish")!=1?-1:300,0);
        }catch(IllegalArgumentException e){finish(company,id,lease,"NEEDS_ACTION",e.getMessage(),-1,0);}catch(RuntimeException e){fault(company,id,lease,e);}
    }
    public int wakeStock(String company){
        int awakened=0;
        List<Map<String,Object>> signals=data(inventory.stockSignals(company,SecurityConstants.INNER));
        for(Map<String,Object> signal:signals){
            // Bounded wakes. Keep the signal until every matching sleeping task has been scheduled.
            List<Long> ids=repository.db().queryForList("SELECT DISTINCT t.order_id FROM oms_order_line l JOIN oms_order_auto_allocation t ON t.company_code=l.company_code AND t.order_id=l.order_id JOIN oms_order o ON o.company_code=t.company_code AND o.id=t.order_id LEFT JOIN oms_order_allocation_config ac ON ac.company_code=o.company_code AND ac.channel_id=o.channel_id WHERE l.company_code=? AND l.sku_sn=? AND t.state='WAIT_STOCK' AND t.paused=0 AND JSON_CONTAINS(COALESCE(o.allocation_policy,ac.policy_json),JSON_QUOTE(?),'$.warehouse_order') ORDER BY t.order_id LIMIT 50",Long.class,company,signal.get("sku_sn"),signal.get("store_code"));
            if(!ids.isEmpty()){
                String marks=String.join(",",Collections.nCopies(ids.size(),"?"));List<Object> args=new ArrayList<>();args.add(company);args.addAll(ids);
                repository.db().update("UPDATE oms_platform_order p JOIN oms_order o ON o.company_code=p.company_code AND o.platform_order_id=p.id SET p.next_retry_at=NOW() WHERE o.company_code=? AND o.id IN ("+marks+")",args.toArray());
                awakened+=repository.db().update("UPDATE oms_order_auto_allocation SET state='PENDING',next_attempt=NOW(),trigger_name='相关虚拟仓库存增加' WHERE company_code=? AND order_id IN ("+marks+") AND state='WAIT_STOCK' AND paused=0",args.toArray());
            }
            if(ids.size()<50){Map<String,Object> ack=new LinkedHashMap<>(signal);data(inventory.ackStockSignal(company,ack,SecurityConstants.INNER));}
        }return awakened;
    }
    public Map<String,Object> action(String company,long id,String action,long version,String actor){
        require(Arrays.asList("retry","pause","resume").contains(action),"自动分仓操作无效");
        new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource())).execute(status->{
            List<Map<String,Object>> found=repository.db().queryForList("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",company,id);require(found.size()==1,"业务订单不存在或无权访问");Map<String,Object> o=found.get(0);
            require(number(o,"control_version")==version,"订单已变化，请刷新后重试");require("WAIT_ALLOCATION".equals(text(o,"status"))&&!"FULL".equals(text(o,"allocation_status")),"订单无需继续自动分仓");
            if("pause".equals(action))require(repository.db().update("UPDATE oms_order_auto_allocation SET paused=1 WHERE company_code=? AND order_id=?",company,id)==1,"本单尚无自动分仓任务");
            else{enqueue(repository.db(),company,id,"人工立即重试");Map<String,Object> policy=decode(order(company,id).get("current_policy"));require("AUTO".equals(text(policy,"execution_mode"))&&number(policy,"enabled")==1,"请先开启店铺自动分仓");require(number(o,"allocation_quantity")==0||number(policy,"auto_replenish")==1,"请先开启自动补充分仓，或人工预览余量");
                require(repository.db().update("UPDATE oms_order_auto_allocation SET paused=0,state='PENDING',next_attempt=NOW(),failures=0,reason='',trigger_name=? WHERE company_code=? AND order_id=? AND (lease_until IS NULL OR lease_until<NOW())","resume".equals(action)?"人工恢复自动分仓":"人工立即重试",company,id)==1,"自动分仓正在处理，请稍后刷新");}
            repository.db().update("UPDATE oms_order SET control_version=control_version+1 WHERE company_code=? AND id=?",company,id);
            repository.log(company,number(o,"platform_order_id"),"AUTO_ALLOCATE_ACTION","SUCCESS","pause".equals(action)?"暂停本单后续自动分仓；已提交的占用不会撤销":"resume".equals(action)?"恢复本单自动分仓":"已提交自动分仓重试",actor);return null;
        });return report(company,id);
    }
    public Map<String,Object> report(String company,long id){List<Map<String,Object>> rows=new ArrayList<>();Map<String,Object> o=order(company,id);rows.add(o);attach(repository.db(),company,rows);return (Map<String,Object>)o.get("auto_allocation");}
    public Map<String,Object> taskState(String company,long id){return repository.db().queryForMap("SELECT state,reason FROM oms_order_auto_allocation WHERE company_code=? AND order_id=?",company,id);}
    public static void attach(JdbcTemplate db,String company,List<Map<String,Object>> orders){
        if(orders.isEmpty())return;List<Object> args=new ArrayList<>();args.add(company);for(Map<String,Object> o:orders)args.add(o.get("id"));Map<Long,Map<String,Object>> tasks=new HashMap<>();
        for(Map<String,Object> t:db.queryForList("SELECT order_id,state,paused,attempts,failures,reason,trigger_name,next_attempt,last_attempt,create_time FROM oms_order_auto_allocation WHERE company_code=? AND order_id IN ("+String.join(",",Collections.nCopies(orders.size(),"?"))+")",args.toArray()))tasks.put(number(t,"order_id"),t);
        for(Map<String,Object> o:orders){Map<String,Object> task=tasks.get(number(o,"id"));if(task==null){task=new LinkedHashMap<>();task.put("state","AUTO".equals(text(o,"auto_execution_mode"))&&number(o,"auto_enabled")==1?"WAIT_CONDITION":"MANUAL");task.put("reason","AUTO".equals(text(o,"auto_execution_mode"))&&number(o,"auto_enabled")==1?"等待审核通过或逻辑预占更新触发；可立即重试":"当前为手动分仓");}else if(number(task,"paused")==1)task.put("state","PAUSED");task.put("execution_mode",text(o,"auto_execution_mode"));task.put("auto_replenish",number(o,"auto_replenish"));task.put("enabled",number(o,"auto_enabled"));o.put("auto_allocation",task);}
    }
}
