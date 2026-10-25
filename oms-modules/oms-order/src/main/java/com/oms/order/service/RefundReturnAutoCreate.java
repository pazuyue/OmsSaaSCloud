package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Review-created durable work. Only an unambiguous original purchase source can be selected. */
@Service public class RefundReturnAutoCreate {
    @Resource RefundService refunds;
    @Resource RefundParcelPolicy policies;
    @Resource RefundGoodsService goods;
    @Resource RefundBundleReturns bundles;
    private OrderRepository r(){return refunds.repository;}

    void enqueue(String c,long id){
        Map<String,Object> b=refunds.business(c,id,true),p=policies.get(c,number(b,"channel_id"));
        if(number(p,"auto_create")!=1||!"APPROVED".equals(text(b,"review_state"))||!"RETURN".equals(text(b,"goods_action"))||"DONE".equals(text(b,"goods_state")))return;
        if(!Arrays.asList("REFUND","REFUND_AND_RETURN").contains(text(refunds.platform(c,number(b,"platform_refund_id"),false),"dispute_type")))return;
        r().db().update("INSERT INTO oms_refund_return_auto_task(company_code,refund_id,platform_version,policy_revision,policy) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE platform_version=VALUES(platform_version),policy_revision=VALUES(policy_revision),policy=VALUES(policy),state='PENDING',attempts=0,message='',plan_id=NULL,next_attempt=NOW(),lease_token=NULL,lease_until=NULL",c,id,b.get("approved_version"),p.get("revision"),encode(p));
        refunds.event(c,id,"RETURN_AUTO_QUEUED","已提交自动创建商品退回安排；采用店铺规则版本 "+p.get("revision"),"自动退回安排");
    }
    public void retry(String c,long id,Map<String,Object> input){
        Map<String,Object> hint=refunds.business(c,id,false);
        refunds.tx().execute(tx->{refunds.lockOriginal(c,number(hint,"channel_id"),text(hint,"platform_tid"));refunds.platform(c,number(hint,"platform_refund_id"),true);Map<String,Object> b=refunds.business(c,id,true);
            require(number(b,"control_version")==number(input,"control_version"),"退款已变化，请刷新");
            Map<String,Object> task=task(c,id,true);require(Arrays.asList("MANUAL","RETRY","CANCELED").contains(text(task,"state")),"当前自动创建任务无需重试");
            require(number(policies.get(c,number(b,"channel_id")),"auto_create")==1,"店铺尚未开启审核后自动创建退回安排");
            require("APPROVED".equals(text(b,"review_state"))&&"RETURN".equals(text(b,"goods_action"))&&!"DONE".equals(text(b,"goods_state")),"请先核对当前退款审核与退回方案");
            enqueue(c,id);return null;});
    }
    Map<String,Object> task(String c,long id,boolean lock){List<Map<String,Object>> found=r().db().query("SELECT * FROM oms_refund_return_auto_task WHERE company_code=? AND refund_id=?"+(lock?" FOR UPDATE":""),ROW,c,id);require(found.size()==1,"本退款尚无自动创建任务；开启规则仅影响后续审核");return found.get(0);}
    static void decorate(OrderRepository repository,String c,List<Map<String,Object>> rows){
        if(rows.isEmpty())return;Map<Long,Map<String,Object>> byId=new HashMap<>();List<Object> args=new ArrayList<>();args.add(c);
        for(Map<String,Object> row:rows){byId.put(number(row,"id"),row);args.add(row.get("id"));}
        for(Map<String,Object> task:repository.db().query("SELECT refund_id,state,attempts,message,plan_id,update_time FROM oms_refund_return_auto_task WHERE company_code=? AND refund_id IN ("+String.join(",",Collections.nCopies(rows.size(),"?"))+")",ROW,args.toArray()))byId.get(number(task,"refund_id")).put("return_auto",task);
    }
    public void scan(String c){
        List<Long> ids=r().db().queryForList("SELECT refund_id FROM oms_refund_return_auto_task WHERE company_code=? AND state IN ('PENDING','RETRY') AND next_attempt<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY state,next_attempt,refund_id LIMIT 20",Long.class,c);
        if(ids.isEmpty())return;
        Set<Long> enabledShops=new HashSet<>();
        try{for(Map<String,Object> shop:goods.catalog.channels(c))if(number(shop,"enabled")==1)enabledShops.add(number(shop,"channel_id"));}
        catch(RuntimeException unavailable){for(Long id:ids){String lease=token();if(claim(c,id,lease))retryFailure(c,id,lease);}return;}
        for(Long id:ids)process(c,id,enabledShops);
    }
    private boolean claim(String c,long id,String lease){return r().db().update("UPDATE oms_refund_return_auto_task SET lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 90 SECOND),attempts=attempts+1 WHERE company_code=? AND refund_id=? AND state IN ('PENDING','RETRY') AND next_attempt<=NOW() AND (lease_until IS NULL OR lease_until<NOW())",lease,c,id)==1;}
    void processOne(String c,long id){Map<String,Object> b=refunds.business(c,id,false);if(r().db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_auto_task WHERE company_code=? AND refund_id=? AND state IN ('PENDING','RETRY') AND next_attempt<=NOW()",Long.class,c,id)>0){policies.catalog.validateShop(c,number(b,"channel_id"));process(c,id,Collections.singleton(number(b,"channel_id")));}}
    void process(String c,long id,Set<Long> enabledShops){
        String lease=token();if(!claim(c,id,lease))return;
        try{
            Map<String,Object> task=task(c,id,false),hint=refunds.business(c,id,false),policy=obj(task.get("policy"));
            if(!enabledShops.contains(number(hint,"channel_id"))){finish(c,id,lease,"CANCELED","店铺已停用，自动创建已暂停",null);return;}
            Map<String,Object> current=policies.get(c,number(hint,"channel_id"));
            if(number(current,"auto_create")!=1){finish(c,id,lease,"CANCELED","店铺已关闭自动创建，请按需人工安排",null);return;}
            require(number(current,"revision")==number(task,"policy_revision"),"店铺退货规则已变化，请核对后重试自动创建");
            Map<String,Object> platform=refunds.platform(c,number(hint,"platform_refund_id"),false);
            eligible(hint,platform,task);
            if(autoLucky(c,id,lease,hint,platform,task,policy))return;
            if(hasPurchasePlan(c,id)){finish(c,id,lease,"DONE","已存在购买商品退回安排，沿用原安排",null);return;}
            List<Map<String,Object>> sources=sources(c,hint);
            require(sources.size()==1,"购买商品存在多个发货来源或尚无明确来源，请人工创建退回安排");
            Map<String,Object> source=sources.get(0);require(source.get("bundle_id")==null,"套装需核对退整套或组件，请人工创建退回安排");
            long qty=number(platform,"platform_quantity");require(qty>0&&qty<=100000000,"平台申请数量不明确，请人工确认实际退回数量");
            Map<String,Object> input=m("control_version",hint.get("control_version"),"shipment_line_id",source.get("id"),"quantity",qty,"store_code",required(policy,"create_store_code",64),"return_address",required(policy,"create_return_address",500),"return_contact",required(policy,"create_return_contact",200),"confirmed",true,"request_key","AUTO_RETURN_"+id+"_"+task.get("platform_version"),"reason","退货退款审核通过；唯一原发货来源；按平台申请数量创建","evidence","平台退款 "+hint.get("refund_id")+"；审核版本 "+task.get("platform_version")+"；店铺规则版本 "+task.get("policy_revision"));
            // Remote inventory and catalog checks finish before acquiring business locks.
            Map<String,Object> prepared=goods.prepareCreate(c,id,input);
            prepared.put("automatic",true);
            refunds.tx().execute(tx->{refunds.lockOriginal(c,number(hint,"channel_id"),text(hint,"platform_tid"));Map<String,Object> p=refunds.platform(c,number(hint,"platform_refund_id"),true),b=refunds.business(c,id,true),locked=task(c,id,true);
                require(lease.equals(text(locked,"lease_token"))&&r().db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_auto_task WHERE company_code=? AND refund_id=? AND lease_token=? AND lease_until>NOW()",Long.class,c,id,lease)==1,"自动创建租约已失效");
                eligible(b,p,locked);Map<String,Object> live=policies.get(c,number(b,"channel_id"),true);require(number(live,"auto_create")==1&&number(live,"revision")==number(locked,"policy_revision"),"店铺规则已变化，请核对后重试");
                if(hasPurchasePlan(c,id)){finish(c,id,lease,"DONE","已存在购买商品退回安排，沿用原安排",null);return null;}
                List<Map<String,Object>> fresh=sources(c,b);require(fresh.size()==1&&number(fresh.get(0),"id")==number(source,"id")&&fresh.get(0).get("bundle_id")==null,"原发货来源已变化，请人工核对");
                input.put("control_version",b.get("control_version"));long plan=number(goods.createPrepared(c,id,input,"自动退回安排",prepared),"id");
                finish(c,id,lease,"DONE","已自动创建购买商品退回安排；仓库下发按独立规则执行",plan);return null;});
        }catch(IllegalArgumentException invalid){finish(c,id,lease,"MANUAL",invalid.getMessage(),null);}
        catch(RuntimeException unavailable){retryFailure(c,id,lease);}
    }
    private boolean autoLucky(String c,long id,String lease,Map<String,Object> b,Map<String,Object> platform,Map<String,Object> task,Map<String,Object> policy){
        List<Map<String,Object>> parents=r().db().query("SELECT * FROM oms_order_bundle WHERE company_code=? AND order_id=? AND platform_oid=? AND business_type='LUCKY_BAG'",ROW,c,b.get("order_id"),b.get("platform_oid"));if(parents.isEmpty())return false;require(parents.size()==1&&bundles!=null,"福袋原销售来源需人工核对");Map<String,Object> parent=parents.get(0);
        List<Map<String,Object>> old=r().db().query("SELECT request_json FROM oms_refund_bundle_return WHERE company_code=? AND refund_id=? AND bundle_id=?",ROW,c,id,parent.get("id"));Map<String,Object> input;
        if(!old.isEmpty())input=new LinkedHashMap<>(obj(old.get(0).get("request_json")));else{require(number(platform,"platform_quantity")==number(parent,"quantity"),"部分福袋退款需人工选择原袋，不能自动随机决定退回内容");input=m("bundle_id",parent.get("id"),"quantity",parent.get("quantity"),"store_code",required(policy,"create_store_code",64),"return_address",required(policy,"create_return_address",500),"return_contact",required(policy,"create_return_contact",200),"reason","审核通过，平台申请全部原福袋；按原袋实际出库来源创建","evidence","退款 "+b.get("refund_id")+"；审核版本 "+task.get("platform_version")+"；店铺规则版本 "+task.get("policy_revision"));}
        input.put("confirmed",true);input.put("control_version",b.get("control_version"));bundles.createFenced(c,id,input,"自动退回安排",()->{Map<String,Object> locked=task(c,id,true);require(lease.equals(text(locked,"lease_token"))&&r().db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_auto_task WHERE company_code=? AND refund_id=? AND lease_token=? AND lease_until>NOW()",Long.class,c,id,lease)==1,"自动创建租约已失效");Map<String,Object> live=policies.get(c,number(b,"channel_id"),true);require(number(live,"auto_create")==1&&number(live,"revision")==number(locked,"policy_revision"),"店铺规则已变化，请核对后重试");eligible(refunds.business(c,id,true),refunds.platform(c,number(b,"platform_refund_id"),true),locked);});
        finish(c,id,lease,"DONE","已按全部原袋实际出库组成创建退回安排；仓库下发按独立规则执行",null);return true;
    }
    private void retryFailure(String c,long id,String lease){Map<String,Object> task=task(c,id,false);boolean exhausted=number(task,"attempts")>=5;finish(c,id,lease,exhausted?"MANUAL":"RETRY",exhausted?"自动创建多次未完成，请核对服务状态或人工创建退回安排":"库存或仓库服务暂不可用，稍后自动重试",null);}
    private void eligible(Map<String,Object> b,Map<String,Object> p,Map<String,Object> task){
        require("APPROVED".equals(text(b,"review_state"))&&number(b,"approved_version")==number(p,"version")&&number(task,"platform_version")==number(p,"version")&&"VALID".equals(text(p,"source_state")),"退款来源或审核版本已变化，请重新审核");
        require(Arrays.asList("REFUND","REFUND_AND_RETURN").contains(text(p,"dispute_type"))&&"RETURN".equals(text(b,"goods_action"))&&!Arrays.asList("DONE","CANCELED").contains(text(b,"goods_state"))&&!Arrays.asList("CLOSED","CANCELED").contains(text(b,"state"))&&!"CLOSED".equals(text(p,"platform_status")),"当前退款方案无需自动创建退回安排，请人工核对");
    }
    private boolean hasPurchasePlan(String c,long id){return !r().db().queryForList("SELECT id FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND gift_plan_id IS NULL LIMIT 1",c,id).isEmpty();}
    private List<Map<String,Object>> sources(String c,Map<String,Object> b){return r().db().query("SELECT sl.id,ol.bundle_id FROM oms_order_shipment s JOIN oms_order_shipment_line sl ON sl.company_code=s.company_code AND sl.shipment_id=s.id JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE s.company_code=? AND s.order_id=? AND ol.platform_oid=? AND ol.gift_plan_id IS NULL AND ol.exchange_id IS NULL AND ol.business_type='SALE' AND s.business_type='SALE' AND s.exchange_id IS NULL AND sl.shipped_quantity>0 ORDER BY sl.id LIMIT 2",ROW,c,b.get("order_id"),b.get("platform_oid"));}
    private void finish(String c,long id,String lease,String state,String message,Long plan){String safe=message==null?"请人工核对退回安排":message.substring(0,Math.min(message.length(),500));
        refunds.tx().execute(tx->{int changed=r().db().update("UPDATE oms_refund_return_auto_task SET state=?,message=?,plan_id=?,next_attempt=DATE_ADD(NOW(),INTERVAL 60 SECOND),lease_token=NULL,lease_until=NULL WHERE company_code=? AND refund_id=? AND lease_token=?",state,safe,plan,c,id,lease);
            if(changed==1)refunds.event(c,id,"RETURN_AUTO_RESULT",safe,"自动退回安排");return null;});
    }
}
