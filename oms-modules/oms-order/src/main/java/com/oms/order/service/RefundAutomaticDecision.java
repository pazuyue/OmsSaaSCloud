package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Explicit merchant permission, authoritative money and actual stock facts; never reason-keyword inference. */
@Service public class RefundAutomaticDecision {
    @Resource RefundService refunds;
    @Resource RefundWorkspace workspace;
    @Resource RefundParcelPolicy parcels;
    @Resource RefundMoneyAuthorizations moneyAuthorizations;
    @Resource RefundAutomaticReturnDecision returnDecisions;
    boolean decide(String c,long id,Map<String,Object> expected){
        Map<String,Object> hint=refunds.business(c,id,false);
        return refunds.tx().execute(tx->{
            refunds.lockOriginal(c,number(hint,"channel_id"),text(hint,"platform_tid"));
            Map<String,Object> b=refunds.business(c,id,true),p=refunds.platform(c,number(b,"platform_refund_id"),true);
            Map<String,Object> policy=refunds.repository.db().queryForMap("SELECT enabled,revision FROM oms_refund_flow_policy WHERE company_code=? AND channel_id=? FOR UPDATE",c,b.get("channel_id"));
            require(number(policy,"enabled")==1&&number(policy,"revision")==number(expected,"revision"),"店铺自动决策规则已变化，请重新核对");
            if(!"PENDING".equals(text(b,"review_state"))||!Arrays.asList("REFUND","REFUND_AND_RETURN").contains(text(p,"dispute_type"))||!"WAIT_SELLER_AGREE".equals(text(p,"platform_status"))||!text(p,"special_type").isEmpty())return false;
            require("VALID".equals(text(p,"source_state")),"平台退款资料异常，暂停自动确认");
            require(refunds.repository.db().queryForObject("SELECT COUNT(*) FROM oms_platform_order WHERE company_code=? AND id=? AND change_pending=1",Long.class,c,b.get("original_platform_order_id"))==0,"原订单有待核对变更，暂停自动确认退款方案");
            RefundMoneyScope moneyScope=RefundMoneyScope.inspect(refunds.repository,c,b);moneyScope.requireAvailable(p.get("refund_fee"),true);
            if("REFUND_AND_RETURN".equals(text(p,"dispute_type")))return number(expected,"auto_return_review")==1&&returnDecisions.adopt(c,b,p,expected);
            if(number(expected,"auto_difference")==1&&moneyAuthorizations.adopt(c,b,p,workspace))return true;
            Map<String,Object> context=RefundFulfillment.context(refunds.repository,c,b);require(!Boolean.TRUE.equals(context.get("uncertain")),"实际出库结果尚未确认，先查询仓库，暂停自动确认方案");
            Map<String,Object> review=m("control_version",b.get("control_version"),"platform_version",p.get("version"),"purpose","OTHER","goods_action","KEEP","reason","命中商家授权的小额免退货规则","evidence","店铺规则版本 "+expected.get("revision")+"；原实付 "+moneyScope.paid+"；其他退款占用 "+moneyScope.used);
            if(number(expected,"small_refund_enabled")==1&&moneyScope.used.add(money(p.get("refund_fee"))).compareTo(money(expected.get("small_refund_limit")))<=0){
                workspace.review(c,id,review,"自动售后规则");return true;
            }
            if(number(expected,"auto_shipped_cancel")!=1){
                if(number(expected,"small_refund_enabled")==1)throw new IllegalArgumentException("本子单累计已退或已审核 "+moneyScope.used+" 元，加本次 "+money(p.get("refund_fee"))+" 元超过小额免退上限 "+money(expected.get("small_refund_limit"))+" 元，请人工确认用途与货物方案");
                return false;
            }
            if(!Arrays.asList("SHIPPED","PART_SHIPPED").contains(text(context,"stage")))return false;
            require(moneyScope.priorMoneySettled,"同一子订单仍有未确认或非纯资金退款，暂停自动取消购买");
            require(money(p.get("refund_fee")).compareTo(moneyScope.remaining)==0,"未命中小额免退规则（子单累计 "+moneyScope.used.add(money(p.get("refund_fee")))+" 元，上限 "+money(expected.get("small_refund_limit"))+" 元），且本次不是剩余实付全额 "+moneyScope.remaining+" 元，请人工确认货物范围");
            // Do not guess a bag/kit sales quantity from component counts.
            List<Map<String,Object>> lines=list(context.get("lines"));require(!lines.isEmpty()&&lines.stream().allMatch(l->l.get("bundle_id")==null),"套装或福袋自动取消需核对原套/袋范围，请人工确认");
            require(number(p,"platform_quantity")==number(context,"required_quantity"),"平台申请数量与剩余购买数量不一致，暂停自动取消购买");
            Map<String,Object> raw=parse(text(p,"payload"));String received=text(raw,"good_status");
            require(Arrays.asList("BUYER_RECEIVED","BUYER_NOT_RECEIVED").contains(received),"平台未明确买家收货状态，暂停自动选择拦截或退回");
            String action="BUYER_RECEIVED".equals(received)?"RETURN":"INTERCEPT";
            require(!"INTERCEPT".equals(action)||number(expected,"auto_intercept")==1,"已出库商品未签收，店铺尚未授权自动物流拦截");
            Map<String,Object> returns=parcels.get(c,number(b,"channel_id"));require(number(returns,"auto_create")==1,"请先开启审核后自动创建退回安排");
            String store=required(returns,"create_store_code",64),address=required(returns,"create_return_address",500),contact=required(returns,"create_return_contact",200);
            List<Map<String,Object>> tasks=new ArrayList<>();
            for(Map<String,Object> line:lines){
                if(number(line,"unshipped_quantity")>0)tasks.add(m("action","CANCEL","order_line_id",line.get("order_line_id"),"quantity",line.get("unshipped_quantity")));
                for(Map<String,Object> source:list(line.get("sources")))if(number(source,"shipped_quantity")>0)tasks.add(m("action",action,"order_line_id",line.get("order_line_id"),"shipment_line_id",source.get("shipment_line_id"),"quantity",source.get("shipped_quantity"),"store_code",store,"return_address",address,"return_contact",contact));
            }
            require(!tasks.isEmpty()&&tasks.size()<=100,"自动分项数量超限或来源缺失，请人工确认");
            review.putAll(m("purpose","CANCEL","goods_action","MIXED","goods_tasks",tasks,"reason","命中商家授权：已出库或部分出库，申请剩余商品实付全额取消购买","evidence","规则版本 "+expected.get("revision")+"；原实付 "+moneyScope.paid+"；前次退款 "+moneyScope.used+"；剩余 "+moneyScope.remaining+"；买家收货状态 "+received));
            workspace.review(c,id,review,"自动售后规则");return true;
        });
    }
    /** Only replace an entirely unexecuted cancellation. Executed stock/warehouse intents keep their audit scope. */
    boolean replanOvertaken(String c,Map<String,Object> task,Map<String,Object> hint){
        if(task.get("command_json")!=null||task.get("cutoff_id")!=null)return false;
        return refunds.tx().execute(tx->{
            refunds.lockOriginal(c,number(hint,"channel_id"),text(hint,"platform_tid"));
            Map<String,Object> b=refunds.business(c,number(hint,"id"),true);
            Map<String,Object> rules=new LinkedHashMap<>(RefundFlowAutomation.defaults());
            List<Map<String,Object>> policies=refunds.repository.db().query("SELECT enabled,revision,policy_json FROM oms_refund_flow_policy WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,c,b.get("channel_id"));
            if(policies.isEmpty())return false;Map<String,Object> row=policies.get(0);rules.putAll(obj(row.get("policy_json")));rules.putAll(m("enabled",row.get("enabled"),"revision",row.get("revision")));
            if(number(rules,"enabled")!=1||number(rules,"auto_shipped_cancel")!=1||!"APPROVED".equals(text(b,"review_state"))||!"自动售后规则".equals(text(b,"review_by")))return false;
            Map<String,Object> context=RefundFulfillment.context(refunds.repository,c,b);
            if(!Arrays.asList("SHIPPED","PART_SHIPPED").contains(text(context,"stage"))||Boolean.TRUE.equals(context.get("uncertain")))return false;
            List<Map<String,Object>> active=refunds.repository.db().query("SELECT * FROM oms_refund_goods_task WHERE company_code=? AND refund_id=? AND state<>'CANCELED' ORDER BY id FOR UPDATE",ROW,c,b.get("id"));
            if(active.isEmpty()||active.stream().anyMatch(t->!"CANCEL".equals(text(t,"action"))||!Arrays.asList("PENDING","PROCESSING").contains(text(t,"state"))||t.get("command_json")!=null||t.get("cutoff_id")!=null||t.get("return_plan_id")!=null))return false;
            Map<String,Object> platform=refunds.platform(c,number(b,"platform_refund_id"),true);
            require(number(b,"approved_version")==number(platform,"version")&&text(task,"approved_hash").equals(text(b,"approved_hash")),"平台申请已变化，不能自动替换原截单范围");
            refunds.repository.db().update("UPDATE oms_refund_goods_task SET state='CANCELED',result_message='实际出库抢先完成，未执行原截单；按最新真实来源自动重新核定' WHERE company_code=? AND refund_id=? AND state<>'CANCELED'",c,b.get("id"));
            refunds.repository.db().update("UPDATE oms_refund SET review_state='PENDING',control_version=control_version+1 WHERE company_code=? AND id=?",c,b.get("id"));
            require(decide(c,number(b,"id"),rules),"出库后不能自动确认货物方案，请人工核对");
            refunds.event(c,number(b,"id"),"AUTO_SCOPE_REPLAN","仓库出库先于截单；原任务未执行且未释放库存，已按实际出库来源改为追回方案","自动售后规则");return true;
        });
    }
}
