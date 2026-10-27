package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Called under the original-order mutex. Existing goods tasks reserve every physical source atomically. */
@Service public class RefundAutomaticReturnDecision {
    @Resource RefundService refunds;
    @Resource RefundWorkspace workspace;
    @Resource RefundParcelPolicy parcels;
    boolean adopt(String c,Map<String,Object> b,Map<String,Object> p,Map<String,Object> rules){
        require(money(p.get("refund_fee")).compareTo(money(rules.get("return_review_limit")))<=0,"本次退货退款超过本地自动审核金额上限，请人工核对");
        Map<String,Object> context=RefundFulfillment.context(refunds.repository,c,b);require(!Boolean.TRUE.equals(context.get("uncertain"))&&"SHIPPED".equals(text(context,"stage")),"退货退款自动审核仅接受原购买商品已全部明确出库的申请");
        List<Map<String,Object>> lines=RefundDispositionService.rows(context.get("lines"));require(!lines.isEmpty(),"原购买商品来源缺失");long quantity=number(p,"platform_quantity");require(quantity>0,"平台退货数量不明确，不能从退款金额推算");
        List<Map<String,Object>> parents=refunds.repository.db().query("SELECT id,quantity,business_type FROM oms_order_bundle WHERE company_code=? AND order_id=? AND platform_oid=? ORDER BY id LIMIT 2",ROW,c,b.get("order_id"),b.get("platform_oid"));
        boolean bundle=!parents.isEmpty();if(bundle){require(parents.size()==1&&quantity==number(parents.get(0),"quantity"),"套装或福袋自动退回只支持全部原套/袋，部分范围需人工核对");for(Map<String,Object> line:lines)require(number(line,"bundle_id")==number(parents.get(0),"id"),"原组合与实际组件归属不一致，暂停自动审核");}
        else require(lines.size()==1&&lines.get(0).get("bundle_id")==null&&quantity<=number(lines.get(0),"shipped_quantity"),"平台申请数量超出明确的原购买商品实发范围");
        Map<String,Object> policy=parcels.get(c,number(b,"channel_id"));require(number(policy,"auto_create")==1,"自动审核退货退款须开启自动创建退回安排");
        String store=required(policy,"create_store_code",64),address=required(policy,"create_return_address",500),contact=required(policy,"create_return_contact",200);
        List<Map<String,Object>> tasks=new ArrayList<>();
        if(!bundle){List<Map<String,Object>> actual=RefundReturnSources.available(refunds.repository,c,b,quantity);require(actual.size()==1,"实际可退发货来源不唯一或额度不足，请核对原销售及已完成换货的实际商品");Map<String,Object> source=actual.get(0);require(source.get("bundle_id")==null,"原组合商品须按全部原组件核对");tasks.add(m("action","RETURN","order_line_id",source.get("order_line_id"),"shipment_line_id",source.get("id"),"quantity",quantity,"store_code",store,"return_address",address,"return_contact",contact));}
        else for(Map<String,Object> line:lines){
            List<Map<String,Object>> sources=RefundDispositionService.rows(line.get("sources"));sources.removeIf(s->number(s,"shipped_quantity")==0);require(sources.size()==1,"同一实际商品有多个发货来源，暂停自动选择退回来源");Map<String,Object> source=sources.get(0);
            tasks.add(m("action","RETURN","order_line_id",line.get("order_line_id"),"shipment_line_id",source.get("shipment_line_id"),"quantity",bundle?line.get("shipped_quantity"):quantity,"store_code",store,"return_address",address,"return_contact",contact));
        }
        require(tasks.size()<=100,"自动退回组件超过100项，请人工核对范围");
        workspace.review(c,number(b,"id"),m("control_version",b.get("control_version"),"platform_version",p.get("version"),"purpose","CANCEL","goods_action","MIXED","goods_tasks",tasks,"reason","命中商家授权：明确退货退款申请，按原实际出库来源自动确认退回范围","evidence","规则版本 "+rules.get("revision")+"；平台申请金额 "+p.get("refund_fee")+"；申请销售数量 "+quantity+(bundle?"；全部原套/袋实际组件，不重新分配福袋内容":"；按平台申请数量退回，不按金额推算")),"自动售后规则");return true;
    }
}
