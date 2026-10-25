package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** Exact merchant mappings and recorded business authorizations, never SKU-name or price guesses. */
@Service public class ExchangeAutomaticDecision {
    @Resource OrderRepository repository;
    @Resource ExchangeService exchanges;
    @Resource ExchangeFlowPolicy policies;
    @Resource ExchangeContinuationService continuations;
    static boolean recoverable(Map<String,Object> e){for(String code:text(e,"problem_codes").split(","))if(!code.isEmpty()&&!Arrays.asList("SKU_UNMATCHED","STOCK_SHORTAGE").contains(code)&&!(e.get("approved_version")==null&&"QUANTITY_MISMATCH".equals(code)))return false;return true;}
    Map<String,Object> decide(String c,Map<String,Object> e,Map<String,Object> p){
        require(number(e,"requested_quantity")>0&&number(e,"requested_quantity")<=number(p,"quantity_limit"),"申请数量超过店铺自动换货上限，请人工核对");
        require(e.get("order_id")!=null&&e.get("order_line_id")!=null,"原订单或子订单尚未唯一关联，请先匹配");
        require(number(e,"speed_exchange")==0||"INTERNAL".equals(text(e,"source_kind")),"极速或未知换货类型需在平台核对");
        require(e.get("linked_exchange_id")==null,"已关联其他协商处理单，请在关联单核对，不重复换出");
        require(!Arrays.asList("4","5","13","14").contains(text(e,"platform_status")),"平台申请已关闭、转退款或已有换出事实，禁止新建自动方案");
        require(recoverable(e),"换货存在来源或订单冲突："+text(e,"problem_message"));
        require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_refund WHERE company_code=? AND channel_id=? AND platform_tid=? AND platform_oid=? AND NOT (review_state='APPROVED' AND purpose IN ('DIFFERENCE','COMPENSATION','POSTAGE','PRICE_PROTECTION') AND goods_action='KEEP') AND state NOT IN ('CLOSED','REJECTED')",Long.class,c,e.get("channel_id"),e.get("platform_tid"),e.get("platform_oid"))==0,"原子单存在未核对或涉及货物的退款，需先处理冲突售后");
        Map<String,Object> payload=object(e.get("payload"));boolean internal="INTERNAL".equals(text(e,"source_kind")),unshipped=ExchangeFulfillment.unshipped(e);
        if(internal){require(ExchangeFlowPolicy.on(p,"auto_internal_review"),"店铺未启用协商换货自动确认方案");require(Boolean.TRUE.equals(payload.get("automation_authorized"))&&!text(payload,"authorization_reference").trim().isEmpty(),"协商换货缺少明确业务授权编号；请补齐授权后重新评估");}
        else require(ExchangeFlowPolicy.on(p,"auto_review"),"店铺未启用平台换货自动确认方案");
        if(unshipped)require(ExchangeFlowPolicy.on(p,"auto_unshipped"),"店铺未启用未发货换品自动处理");
        Map<String,Object> selectedSource=null;if(!unshipped){List<Map<String,Object>> enough=new ArrayList<>();for(Map<String,Object> line:exchanges.returnableSources(c,e))if(number(line,"claimable_quantity")>=number(e,"requested_quantity")&&(e.get("source_shipment_line_id")==null||number(e,"source_shipment_line_id")==number(line,"source_shipment_line_id")))enough.add(line);require(enough.size()==1,"实际退回来源不唯一或额度不足；不同次发货不可按SKU合并");selectedSource=enough.get(0);}
        String source=selectedSource==null?text(e,"original_sku"):text(selectedSource,"sku_sn"),claim=internal?text(e,"target_sku"):text(e,"target_description");
        if(internal&&claim.isEmpty())claim=text(payload,"target_sku");
        List<Map<String,Object>> exact=new ArrayList<>(),bySku=new ArrayList<>();Object raw=p.get("mappings");if(raw instanceof List)for(Object item:(List<?>)raw){Map<String,Object> m=object(item);if(!source.equals(text(m,"source_sku")))continue;if(claim.equals(text(m,"platform_target")))exact.add(m);if(internal&&claim.equals(text(m,"target_sku")))bySku.add(m);}
        List<Map<String,Object>> choices=exact.isEmpty()&&internal?bySku:exact;
        require(choices.size()==1,"未命中唯一的原SKU与平台目标规则，请明确SKU映射及无需补差依据");
        Map<String,Object> mapping=choices.get(0);require(!text(mapping,"no_surcharge_basis").trim().isEmpty(),"缺少无需补差或免补差的明确业务依据");
        Map<String,Object> input=map("control_version",e.get("control_version"),"target_sku",mapping.get("target_sku"),"return_store",p.get("return_store"),"platform_address_id",p.get("platform_address_id"),"return_address",p.get("return_address"),"return_contact",p.get("return_contact"),"reason","命中商家自动换货规则版本 "+p.get("revision")+"；免补差依据："+text(mapping,"no_surcharge_basis").substring(0,Math.min(400,text(mapping,"no_surcharge_basis").length())),"automatic_policy_revision",p.get("revision"),"automatic_authorization",mapping);
        if(!unshipped){input.put("source_shipment_line_id",selectedSource.get("source_shipment_line_id"));
            Map<String,Object> recipient=internal&&payload.get("replacement_recipient") instanceof Map?new LinkedHashMap<>(object(payload.get("replacement_recipient"))):exchanges.recipient(c,e);
            if(!internal)guardRecipient(payload,recipient,p);
            input.put("replacement_recipient",recipient);input.put("recipient_confirmed",true);
        }
        policies.requireCurrent(c,number(e,"channel_id"),number(p,"revision"),internal?"auto_internal_review":"auto_review");
        return exchanges.review(c,number(e,"id"),input,ExchangeFlowPolicy.REVIEW_ACTOR);
    }
    void continueRound(String c,Map<String,Object> e,Map<String,Object> p){
        require(ExchangeFlowPolicy.on(p,"auto_continue_review"),"继续换出自动审核未启用，请在继续换出申请审核");
        List<Map<String,Object>> pending=repository.db().query("SELECT * FROM oms_exchange_round WHERE company_code=? AND exchange_id=? AND state='REVIEW' ORDER BY round_no LIMIT 2",ROW,c,e.get("id"));require(pending.size()==1,"继续换出待审批次不唯一，请核对原申请");
        Map<String,Object> round=pending.get(0),basis=object(round.get("basis_json"));require(Boolean.TRUE.equals(basis.get("automation_authorized"))&&!text(basis,"authorization_reference").isEmpty(),"本轮未授权自动审核，请人工核对继续换出申请");policies.requireCurrent(c,number(e,"channel_id"),number(p,"revision"),"auto_continue_review");
        continuations.review(c,number(e,"id"),number(round,"round_no"),map("control_version",e.get("control_version"),"decision","APPROVE","refund_confirmed",true,"automatic_policy_revision",p.get("revision"),"reason","商家已授权本轮继续换出；自动规则版本 "+p.get("revision")+"；原退回、截单、剩余数量及退款冲突均重新校验"),ExchangeFlowPolicy.REVIEW_ACTOR);
    }
    private void guardRecipient(Map<String,Object> payload,Map<String,Object> recipient,Map<String,Object> p){
        String supplied=text(payload,"buyer_address"),name=text(payload,"buyer_name"),phone=text(payload,"buyer_phone");
        if(!supplied.isEmpty()){String address=text(recipient,"province")+text(recipient,"city")+text(recipient,"area")+text(recipient,"town")+text(recipient,"detailAddress");require(normalize(supplied).equals(normalize(address))||normalize(supplied).equals(normalize(text(recipient,"detailAddress"))),"平台换出地址与原地址不一致，请人工核对结构化收件信息");}
        else require(ExchangeFlowPolicy.on(p,"allow_original_recipient"),"平台未提供完整换出地址，店铺未授权沿用原收件信息");
        require(name.isEmpty()||name.equals(text(recipient,"name")),"平台收件人已变化，请核对");require(phone.isEmpty()||phone.equals(text(recipient,"mobile")),"平台收件电话已变化，请核对");
    }
    private String normalize(String value){return value.replaceAll("[\\s,，;；]","");}
}
