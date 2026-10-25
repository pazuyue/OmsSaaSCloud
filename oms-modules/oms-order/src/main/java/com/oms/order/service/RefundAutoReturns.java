package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Scoped, idempotent continuation of adopted return plans; no synthetic receipts or stock posting. */
@Service public class RefundAutoReturns {
    @Resource RefundService refunds;
    @Resource RefundGoodsService goods;
    @Resource RefundReturnAutoCreate autoCreate;
    @Resource RefundParcelPolicy policies;
    @Resource RefundParcelAutomation parcels;
    @Resource RefundReturnClient returns;
    void advance(String c,long id,Map<String,Object> rules){
        Map<String,Object> b=refunds.business(c,id,false),policy=policies.get(c,number(b,"channel_id"));
        if(number(policy,"auto_create")==1){autoCreate.processOne(c,id);prepareGifts(c,id,policy);}
        parcels.process(c,id);
        resumeRecordedReceipts(c,id,policy);
        goods.refresh(c,id);
        b=refunds.business(c,id,false);
        if(number(rules,"auto_finish_return")!=1||!"WAIT_QC".equals(text(b,"goods_state")))return;
        if(refunds.repository.gifts()!=null){
            Map<String,Object> giftReport=refunds.repository.gifts().report(c,number(b,"order_id"));
            RefundGiftScope scope=new RefundGiftScope(refunds.repository,c,b,giftReport);
            for(Map<String,Object> line:RefundDispositionService.rows(giftReport.get("lines"))){if(scope.match(line)==RefundGiftScope.Match.OTHER)continue;if(number(line,"cancel_required")>0||number(line,"return_required")>0||number(line,"unarranged_quantity")>0)return;}
        }
        Map<String,Object> hint=b;
        boolean ready=refunds.tx().execute(tx->{refunds.lockOriginal(c,number(hint,"channel_id"),text(hint,"platform_tid"));
            Map<String,Object> live=refunds.business(c,id,true);
            require(refunds.repository.db().queryForObject("SELECT COUNT(*) FROM oms_refund_flow_policy WHERE company_code=? AND channel_id=? AND enabled=1 AND revision=?",Long.class,c,live.get("channel_id"),rules.get("revision"))==1,"自动结案规则已变化，请重新核对");
            return refunds.repository.db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND (quantity<>accepted_quantity OR accepted_quantity<>posted_quantity OR waived_quantity<>0)",Long.class,c,id)==0;});
        if(!ready)return;
        b=refunds.business(c,id,false);
        goods.finish(c,id,m("control_version",b.get("control_version"),"confirmed",true,"reason","命中商家规则：购买商品及关联赠品全部实收、入账且无数量差异，自动结案","evidence","店铺规则版本 "+rules.get("revision")+"；逐项原仓库实收和入账回执"),"自动售后流程");
    }
    void resumeRecordedReceipts(String c,long id,Map<String,Object> policy){
        if(number(policy,"auto_allocate")!=1)return;
        List<Long> affected=refunds.repository.db().queryForList("SELECT id FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND state NOT IN ('CLOSED','RELEASED','STOPPED') AND JSON_UNQUOTE(JSON_EXTRACT(parcel_issue,'$.code'))='LOGISTICS_CHANGED' ORDER BY id LIMIT 100",Long.class,c,id);if(affected.isEmpty())return;
        int checked=0;for(Map<String,Object> parcel:data(returns.parcels(c,id,com.ruoyi.common.core.constant.SecurityConstants.INNER))){
            boolean own=RefundDispositionService.rows(parcel.get("members")).stream().anyMatch(m->number(m,"refund_id")==id&&affected.contains(number(m,"plan_id"))&&number(m,"active")==1);if(!own)continue;
            Map<String,Object> doc=obj(parcel.get("warehouse_document"));if(!"ACCEPTED".equals(text(doc,"dispatch_state"))||!"NONE".equals(text(doc,"cancel_state")))continue;
            for(Map<String,Object> receipt:RefundDispositionService.rows(parcel.get("receipts"))){if(!"WAIT_ALLOCATION".equals(text(receipt,"state"))||number(receipt,"document_id")!=number(doc,"id"))continue;if(++checked>10)return;
                Map<String,Object> preview=data(returns.evaluateAutomatic(c,id,number(parcel,"id"),number(receipt,"id"),m("execute",false),com.ruoyi.common.core.constant.SecurityConstants.INNER));
                if("READY".equals(text(preview,"status")))data(returns.evaluateAutomatic(c,id,number(parcel,"id"),number(receipt,"id"),m("execute",true,"confirmed",true,"parcel_version",preview.get("parcel_version"),"policy_revision",preview.get("policy_revision")),com.ruoyi.common.core.constant.SecurityConstants.INNER));
            }
        }
    }
    private void prepareGifts(String c,long id,Map<String,Object> policy){
        if(refunds.repository.gifts()==null)return;
        Map<String,Object> b=refunds.business(c,id,false);
        if(!"CANCEL".equals(text(b,"purpose"))||!"APPROVED".equals(text(b,"review_state")))return;
        if(refunds.repository.db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND gift_plan_id IS NULL",Long.class,c,id)==0)return;
        Map<String,Object> report=refunds.repository.gifts().report(c,number(b,"order_id"));
        RefundGiftScope scope=new RefundGiftScope(refunds.repository,c,b,report);
        for(Map<String,Object> line:RefundDispositionService.rows(report.get("lines"))){
            long remaining=number(line,"unarranged_quantity");if(remaining<=0)continue;
            RefundGiftScope.Match match=scope.match(line);if(match==RefundGiftScope.Match.OTHER)continue;require(match==RefundGiftScope.Match.OWN,"多笔取消退款涉及同一赠品，原策略不能唯一确定归属，请核对赠品关联后继续；不会挂到先执行的退款");
            for(Map<String,Object> source:list(line.get("sources"))){
                long used=refunds.repository.db().queryForObject("SELECT COALESCE(SUM(quantity-waived_quantity),0) FROM oms_refund_return_plan WHERE company_code=? AND shipment_line_id=?",Long.class,c,source.get("id"));
                long qty=Math.min(remaining,Math.max(0,number(source,"shipped_quantity")-used));if(qty==0)continue;
                b=refunds.business(c,id,false);
                goods.create(c,id,m("control_version",b.get("control_version"),"gift_plan_id",report.get("id"),"shipment_line_id",source.get("id"),"quantity",qty,"store_code",required(policy,"create_store_code",64),"return_address",required(policy,"create_return_address",500),"return_contact",required(policy,"create_return_contact",200),"confirmed",true,"request_key","AUTO_GIFT_RETURN_"+id+"_"+source.get("id"),"reason","已审核取消购买，按原赠品策略自动追回对应已发赠品","evidence","原赠品计划 "+report.get("id")+"；原实际出库来源 "+source.get("id")),"自动售后流程");
                remaining-=qty;if(remaining==0)break;
            }
            require(remaining==0,"应追回赠品缺少可用的原实际出库来源，请核对原退回安排；不会跳过赠品结案付款");
        }
    }
}
