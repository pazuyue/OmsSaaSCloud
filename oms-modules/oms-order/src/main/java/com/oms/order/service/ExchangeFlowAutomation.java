package com.oms.order.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** One bounded business step per durable notification. Existing commands retain their identities. */
@Service public class ExchangeFlowAutomation {
    @Resource OrderRepository repository;
    @Resource ExchangeService exchanges;
    @Resource ExchangeExecution execution;
    @Resource ExchangeFlowPolicy policies;
    @Resource ExchangeAutomaticDecision decisions;
    @Resource ExchangeFulfillment fulfillment;
    @Resource ExchangeAutoReturns returns;
    @Resource ExchangeAutomaticFulfillment shipping;
    @Resource ExchangeOperationJournal journal;
    @Resource ExchangeContinuationService continuations;
    @Resource ExchangeFlowStop stop;
    TransactionTemplate tx(){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));}

    static void changed(OrderRepository r,String c,long id){
        if(r.messaging()==null)return;
        r.db().update("INSERT IGNORE INTO oms_exchange_flow_task(company_code,exchange_id,observed_version) SELECT e.company_code,e.id,e.control_version FROM oms_exchange e JOIN oms_exchange_flow_policy p ON p.company_code=e.company_code AND p.channel_id=e.channel_id AND p.enabled=1 AND p.enabled_after IS NOT NULL AND e.create_time>=p.enabled_after WHERE e.company_code=? AND e.id=?",c,id);
        r.db().update("UPDATE oms_exchange_flow_task t JOIN oms_exchange e ON e.company_code=t.company_code AND e.id=t.exchange_id SET t.observed_version=e.control_version,t.work_version=t.work_version+1,t.state='PENDING',t.next_attempt=NOW() WHERE t.company_code=? AND t.exchange_id=? AND t.observed_version<>e.control_version",c,id);
        List<Long> versions=r.db().queryForList("SELECT work_version FROM oms_exchange_flow_task WHERE company_code=? AND exchange_id=?",Long.class,c,id);
        if(!versions.isEmpty()){long v=versions.get(0);if(r.db().queryForObject("SELECT COUNT(*) FROM oms_message_outbox WHERE company_code=? AND service_code='order' AND message_type='EXCHANGE_FLOW' AND business_id=? AND business_version=?",Long.class,c,String.valueOf(id),v)==0)r.messaging().enqueue(c,"EXCHANGE_FLOW",id,v,"EXFLOW"+id,id+":"+v,0);}
    }
    public void retry(String c,long id){exchanges.row(c,id,false);tx().execute(s->{require(repository.db().update("UPDATE oms_exchange_flow_task SET state='PENDING',work_version=work_version+1,next_attempt=NOW() WHERE company_code=? AND exchange_id=?",c,id)==1,"此HH未纳入新自动规则，请核对店铺启用时间及适用范围");changed(repository,c,id);return null;});}
    public void scan(String c){if(repository.messaging()==null)return;tx().execute(s->{for(Map<String,Object> t:repository.db().query("SELECT t.exchange_id FROM oms_exchange_flow_task t WHERE t.company_code=? AND t.state IN ('PENDING','WAITING') AND t.next_attempt<=NOW() AND (t.lease_until IS NULL OR t.lease_until<NOW()) AND NOT EXISTS(SELECT 1 FROM oms_message_outbox o WHERE o.company_code=t.company_code AND o.service_code='order' AND o.message_type='EXCHANGE_FLOW' AND o.business_id=CAST(t.exchange_id AS CHAR) AND o.business_version=t.work_version AND o.process_state IN ('PENDING','WAITING','PROCESSING')) ORDER BY t.next_attempt,t.exchange_id LIMIT 20 FOR UPDATE",ROW,c)){long id=number(t,"exchange_id");repository.db().update("UPDATE oms_exchange_flow_task SET work_version=work_version+1,next_attempt=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE company_code=? AND exchange_id=?",c,id);changed(repository,c,id);}return null;});}
    Map<String,Object> state(String c,long id,long version,String state,String node,String code,String message){
        String safe=message==null?"等待核对原执行结果":message.substring(0,Math.min(1000,message.length()));
        repository.db().update("UPDATE oms_exchange_flow_task SET wait_since=IF(node=? AND wait_since IS NOT NULL,wait_since,NOW()),state=?,node=?,last_code=?,last_message=?,next_attempt=IF(? IN ('MANUAL','HELD','DONE'),NULL,DATE_ADD(NOW(),INTERVAL 30 SECOND)) WHERE company_code=? AND exchange_id=? AND work_version=?",node,state,node,code,safe,state,c,id,version);
        return map("state",state,"node",node,"code",code,"message",safe);
    }
    public Map<String,Object> step(String c,long id,long version){
        String lease=token();if(repository.db().update("UPDATE oms_exchange_flow_task SET lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) WHERE company_code=? AND exchange_id=? AND work_version=? AND (lease_until IS NULL OR lease_until<NOW())",lease,c,id,version)!=1){List<Long> current=repository.db().queryForList("SELECT work_version FROM oms_exchange_flow_task WHERE company_code=? AND exchange_id=?",Long.class,c,id);return map("state",current.isEmpty()||current.get(0)!=version?"DONE":"WAITING","message","已有新通知或本HH正在执行");}
        String node="REVIEW";
        try{
            Map<String,Object> e=exchanges.row(c,id,false),p=policies.read(c,number(e,"channel_id"));repository.db().update("UPDATE oms_exchange_flow_task SET policy_revision=? WHERE company_code=? AND exchange_id=? AND lease_token=?",p.get("revision"),c,id,lease);
            if(Arrays.asList("4","14").contains(text(e,"platform_status"))){node="STOP";stop.advance(c,e,p);Map<String,Object> now=exchanges.row(c,id,false);if(Arrays.asList("CANCELED","CLOSED").contains(text(now,"state")))return state(c,id,version,"DONE","STOP","PLATFORM_STOPPED","平台申请已结束，原未发履约截停及库存释放已核对，实收实发事实保留");if(repository.db().queryForObject("SELECT COUNT(*) FROM oms_exchange_control WHERE company_code=? AND exchange_id=? AND state='PENDING'",Long.class,c,id)>0)return waitAt(c,id,version,p,"STOP","WAIT_CUTOFF","平台申请已结束并停止新增换出，等待原仓库截单和库存释放确认",number(p,"warehouse_wait_minutes")*60);return state(c,id,version,"MANUAL","STOP","PLATFORM_STOP","平台申请关闭或转退款，已停止新增换出；请核对原出入库截单及关联退款");}
            String pending=text(e,"platform_operation");
            // Read/reconcile issued commands even when rules are disabled. No new intent is created here.
            if(Arrays.asList("PENDING","STOCK_WAIT","SENDING","UNKNOWN").contains(pending)){execution.process(c,id);return waitAt(c,id,version,p,"PLATFORM","PLATFORM_PENDING","核对原平台请求；不重建或重复提交未知结果的命令",number(p,"warehouse_wait_minutes")*60);}
            if(!ExchangeFlowPolicy.on(p,"enabled"))return state(c,id,version,"HELD","POLICY","POLICY_OFF","店铺自动流程已关闭；原实收实发及已发请求继续留痕核对");
            if("CONTINUE_REVIEW".equals(text(e,"control_state"))){decisions.continueRound(c,e,p);return state(c,id,version,"WAITING","CONTINUE_REVIEW","CONTINUE_APPROVED","已重新校验并自动审核本轮剩余换出；复用原退回，不重复入库");}
            if(number(e,"fulfillment_hold")==1)return state(c,id,version,"HELD","CONTROL","HELD","换货已挂起或存在截单、继续换出审核；请在处理原因对应入口核对");
            if(Arrays.asList("CANCELED","CLOSED","REJECTED","COMPLETED").contains(text(e,"state")))return state(c,id,version,"DONE","FINISHED","LOCAL_ENDED","本地换货已结束，原库存及收发事实保留");
            if(!ExchangeAutomaticDecision.recoverable(e))return state(c,id,version,"MANUAL","SOURCE",text(e,"problem_codes"),text(e,"problem_message").isEmpty()?text(e,"last_error"):text(e,"problem_message"));
            if(e.get("approved_version")==null){decisions.decide(c,e,p);return state(c,id,version,"WAITING","REVIEW","AUTO_REVIEWED","已按明确SKU映射、免补差及来源数量规则自动确认方案");}
            ExchangeFulfillment.planUnchanged(e);
            if("FAILED".equals(pending))return state(c,id,version,"MANUAL","PLATFORM","PLATFORM_FAILED","原平台操作已明确失败，请在平台交互记录核对原因；不定时重复提交");
            node="STOCK";if(e.get("fulfillment_line_id")==null)requireFlag(p,"auto_allocate","自动换出库存预占与分仓未启用，请核对规则或从换出发货处理");if(e.get("fulfillment_line_id")==null&&(e.get("stock_receipt")==null||number(object(e.get("stock_receipt")),"reserved_quantity")!=ExchangeRounds.quantityOf(e))&&!fulfillment.reserve(c,id)){return waitAt(c,id,version,p,"STOCK","STOCK_SHORTAGE","换出库存不足，保留完整申请数量；等待补货或核对可用仓",number(p,"stock_wait_minutes")*60);}
            e=exchanges.row(c,id,false);
            if(ExchangeFulfillment.unshipped(e)){require(ExchangeFlowPolicy.on(p,"auto_unshipped"),"店铺未启用未发货换品自动处理");fulfillment.internal(c,id);e=exchanges.row(c,id,false);if("COMPLETED".equals(text(e,"state")))return state(c,id,version,"DONE","FINISHED","INTERNAL_COMPLETED","未发货换品的有效换出已完成或被后续换品替代");if(e.get("fulfillment_line_id")==null)return state(c,id,version,"WAITING","WITHDRAW","WAIT_ORIGINAL_CUTOFF","等待原未发货分仓撤回及旧需求终止确认");return shipping.advance(c,e,p,version);}
            boolean platform="PLATFORM".equals(text(e,"source_kind"));
            if(platform&&"1".equals(text(e,"platform_status"))){node="AGREE";requireFlag(p,"auto_agree","自动同意平台换货未启用");submit(c,e,"AGREE",p);return state(c,id,version,"WAITING","AGREE","AGREE_SUBMITTED","已登记同意换货，等待平台确认；库存预占不代表已发货");}
            node="RETURN";Map<String,Object> received=returns.advance(c,e,p);e=exchanges.row(c,id,false);
            if("FAILED".equals(text(received,"dispatch_state")))return state(c,id,version,"MANUAL","RETURN_DISPATCH","RETURN_DISPATCH_FAILED","原退回仓库单已明确下发失败："+text(received,"last_error")+"；请在原退回单核对后重试");
            if(Arrays.asList("UNKNOWN","SENDING").contains(text(received,"dispatch_state")))return waitAt(c,id,version,p,"RETURN_DISPATCH","RETURN_DISPATCH_UNKNOWN","正在查询原退回仓库单，结果未知不重新建单",number(p,"warehouse_wait_minutes")*60);
            if("PENDING".equals(text(received,"dispatch_state"))){requireFlag(p,"auto_return_dispatch","退回安排已建立，店铺未授权自动下发，请核对原退回单");if(Boolean.TRUE.equals(received.get("shared_after_sale"))&&text(received,"return_sn").startsWith("RSL"))return state(c,id,version,"WAITING","COLLECT","COLLECTING","共同退回正在等待最后新增来源后的归集期限，到期核对全部成员授权再统一下发");}
            if(number(received,"pending_review_count")>0)return state(c,id,version,"MANUAL","RETURN","RETURN_REVIEW","存在待核对实收，请打开退回包裹核对具体商品及数量");
            if(number(received,"posted_quantity")!=number(e,"requested_quantity")||!"COMPLETED".equals(text(received,"receipt_state")))return waitAt(c,id,version,p,"RETURN","WAIT_RETURN","等待原退回商品完整实收入账；平台状态不代替仓库实际收货",number(p,"return_wait_days")*86400);
            node="QC";if(!"PASSED".equals(text(e,"return_qc"))){requireFlag(p,"auto_qc","自动退回验收未启用，请核对实收及良次品后验收");require(!"ISSUE".equals(text(e,"return_qc")),"退回已登记差异，请从差异处理入口确认继续，不能自动覆盖");require(received.get("grades") instanceof Map,"仓库良次品明细尚未完整返回，请核对原实收");Map<String,Object> grades=object(received.get("grades"));require(number(grades,"bad_qty")==0&&number(grades,"good_qty")==number(e,"requested_quantity")&&number(grades,"posted_qty")==number(e,"requested_quantity"),"退回并非全部良品且完整入账，请人工核对质检和数量");policies.requireCurrent(c,number(e,"channel_id"),number(p,"revision"),"auto_qc");execution.qc(c,id,map("control_version",e.get("control_version"),"decision","PASSED","reason","店铺规则授权：可信仓库良品完整入账且无待核对回传，自动确认验收","evidence","自动规则版本 "+p.get("revision")+"；良品数量 "+grades.get("good_qty")),ExchangeFlowPolicy.ACTOR);return state(c,id,version,"WAITING","QC","QC_PASSED","已按商家授权自动确认良品退回验收；未重复增加库存");}
            if(platform&&"3".equals(text(e,"platform_status"))){node="RECEIVED";requireFlag(p,"auto_receive","自动确认平台收货未启用");submit(c,e,"RECEIVED",p);return state(c,id,version,"WAITING","RECEIVED","RECEIVED_SUBMITTED","已登记平台确认收货，等待进入换出阶段");}
            if(platform&&!Arrays.asList("12","13","5").contains(text(e,"platform_status")))return state(c,id,version,"WAITING","PLATFORM","WAIT_PLATFORM_STAGE","已完成本地退回验收，等待平台允许换出；不重复同意或确认收货");
            node="REPLACEMENT";
            List<String> refundBlocks=repository.db().queryForList("SELECT refund_block_reason FROM oms_order_line WHERE company_code=? AND order_id=? AND platform_oid=? AND refund_block_reason<>'' ORDER BY id LIMIT 1",String.class,c,e.get("order_id"),e.get("platform_oid"));
            if(!refundBlocks.isEmpty())return waitAt(c,id,version,p,"REFUND","WAIT_REFUND",refundBlocks.get(0)+"；暂停新增换出，等待该退款货物结果核对完成",number(p,"stock_wait_minutes")*60);
            return shipping.advance(c,e,p,version);
        }catch(IllegalArgumentException invalid){return state(c,id,version,"MANUAL",node,"BUSINESS_CHECK",invalid.getMessage());}
        catch(IllegalStateException remote){String why=remote.getMessage()==null?"关联服务未返回明确结果":remote.getMessage();boolean business=Arrays.asList("必须","不一致","不属于","无效","不能","未启用","请先","请从").stream().anyMatch(why::contains);if(business)return state(c,id,version,"MANUAL",node,"REMOTE_BUSINESS_CHECK",why);return state(c,id,version,"WAITING",node,"SERVICE_WAIT",why);}
        catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(ExchangeFlowAutomation.class).warn("Exchange automatic continuation unavailable: company={}, id={}, node={}",c,id,node,unavailable);return state(c,id,version,"WAITING",node,"SERVICE_WAIT","服务或原命令结果暂未确认，稍后核对原请求；不会重复建单或发送未知命令");}
        finally{repository.db().update("UPDATE oms_exchange_flow_task SET lease_token=NULL,lease_until=NULL WHERE company_code=? AND exchange_id=? AND lease_token=?",c,id,lease);}
    }
    Map<String,Object> waitAt(String c,long id,long v,Map<String,Object> p,String node,String code,String message,long seconds){List<Map<String,Object>> old=repository.db().query("SELECT node,wait_since FROM oms_exchange_flow_task WHERE company_code=? AND exchange_id=?",ROW,c,id);boolean expired=!old.isEmpty()&&node.equals(text(old.get(0),"node"))&&old.get(0).get("wait_since")!=null&&time(text(old.get(0),"wait_since")).plusSeconds(seconds).isBefore(LocalDateTime.now());return state(c,id,v,expired?"MANUAL":"WAITING",node,expired?code+"_TIMEOUT":code,expired?message+"；已超过商家设置的等待期限，请人工处理":message);}
    void requireFlag(Map<String,Object> p,String key,String message){require(ExchangeFlowPolicy.on(p,key),message);}
    void submit(String c,Map<String,Object> e,String action,Map<String,Object> p){policies.requireCurrent(c,number(e,"channel_id"),number(p,"revision"),ExchangeOperationJournal.flag(action));Map<String,Object> input=map("control_version",e.get("control_version"),"operation",action,"reason","商家自动换货规则版本 "+p.get("revision")+"；动作 "+action);Map<String,Object> op=journal.begin(c,e,action,p,input);require("PENDING".equals(text(op,"state")),"本规则的原平台操作已登记或结束，请核对原操作记录");input.put("flow_operation_id",op.get("id"));input.put("automatic_policy_revision",p.get("revision"));try{execution.request(c,number(e,"id"),input,ExchangeFlowPolicy.ACTOR);}catch(RuntimeException ex){journal.update(c,number(op,"id"),"FAILED",ex.getMessage()==null?"平台动作未通过业务校验":ex.getMessage(),null);throw ex;}}
    public Map<String,Object> report(String c,long id){exchanges.row(c,id,false);List<Map<String,Object>> rows=repository.db().query("SELECT state,node,last_code,last_message,wait_since,next_attempt,policy_revision,work_version FROM oms_exchange_flow_task WHERE company_code=? AND exchange_id=?",ROW,c,id);return map("task",rows.isEmpty()?Collections.emptyMap():rows.get(0),"operations",journal.records(c,id));}
}
