package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Exact merchant business evidence. Never infers a difference from a reason or a small amount. */
@Service public class RefundMoneyAuthorizations {
    @Resource RefundService refunds;
    @Resource OrderCatalog catalog;
    private OrderRepository r(){return refunds.repository;}
    public List<Map<String,Object>> list(String c,long channel){catalog.validateShop(c,channel);return r().db().query("SELECT * FROM oms_refund_money_authorization WHERE company_code=? AND channel_id=? ORDER BY id DESC LIMIT 100",ROW,c,channel);}
    public Map<String,Object> create(String c,long channel,Map<String,Object> input,String actor){
        catalog.validateShop(c,channel);require(Boolean.TRUE.equals(input.get("confirmed")),"请明确确认差价业务授权");String tid=required(input,"platform_tid",64),oid=required(input,"platform_oid",64),rid=required(input,"refund_id",64);numeric(tid);numeric(oid);numeric(rid);
        java.math.BigDecimal amount=money(input.get("amount"));require(amount.signum()>0,"差价授权金额必须大于0");String reference=required(input,"reference_sn",100),evidence=required(input,"evidence",1000),until=required(input,"expires_at",19);
        LocalDateTime expires=time(until);require(expires.isAfter(LocalDateTime.now())&&!expires.isAfter(LocalDateTime.now().plusDays(30)),"授权须在未来30天内到期");
        return refunds.tx().execute(tx->{refunds.lockOriginal(c,channel,tid);
            List<Map<String,Object>> orders=r().db().query("SELECT id,document_info FROM oms_order WHERE company_code=? AND channel_id=? AND platform_tid=? ORDER BY id LIMIT 2",ROW,c,channel,tid);require(orders.size()==1,"授权原业务订单不存在或来源不唯一");
            Map<String,Object> doc=OrderDocumentInfo.stored(text(orders.get(0),"document_info"));Map<String,Object> line=RefundDispositionService.rows(doc.get("lines")).stream().filter(l->oid.equals(text(l,"platform_oid"))).findFirst().orElseThrow(()->new IllegalArgumentException("授权原平台子订单不存在"));
            require(text(line,"payment_source").startsWith("平台")&&amount.compareTo(money(line.get("goods_payment")))<=0,"授权金额超出明确的原子单平台商品实付");
            List<Map<String,Object>> old=r().db().query("SELECT * FROM oms_refund_money_authorization WHERE company_code=? AND channel_id=? AND refund_id=? FOR UPDATE",ROW,c,channel,rid);
            if(!old.isEmpty()){Map<String,Object> row=old.get(0);require(tid.equals(text(row,"platform_tid"))&&oid.equals(text(row,"platform_oid"))&&amount.equals(money(row.get("amount")))&&reference.equals(text(row,"reference_sn"))&&evidence.equals(text(row,"evidence")),"该退款已有授权，不可覆盖原金额或依据");return row;}
            long id=r().insert("INSERT INTO oms_refund_money_authorization(company_code,channel_id,platform_tid,platform_oid,refund_id,amount,reference_sn,evidence,expires_at,authorized_by) VALUES (?,?,?,?,?,?,?,?,?,?)",c,channel,tid,oid,rid,amount,reference,evidence,until,actor);
            return r().db().queryForMap("SELECT * FROM oms_refund_money_authorization WHERE company_code=? AND id=?",c,id);
        });
    }
    public void revoke(String c,long channel,long id,long version,String actor){catalog.validateShop(c,channel);require(r().db().update("UPDATE oms_refund_money_authorization SET state='REVOKED',revoked_by=?,control_version=control_version+1 WHERE company_code=? AND channel_id=? AND id=? AND control_version=? AND state='ACTIVE'",actor,c,channel,id,version)==1,"授权已采用、撤销或版本已变化，不能撤销");}
    boolean adopt(String c,Map<String,Object> b,Map<String,Object> p,RefundWorkspace workspace){
        List<Map<String,Object>> rows=r().db().query("SELECT * FROM oms_refund_money_authorization WHERE company_code=? AND channel_id=? AND refund_id=? FOR UPDATE",ROW,c,b.get("channel_id"),b.get("refund_id"));if(rows.isEmpty())return false;
        Map<String,Object> a=rows.get(0);require(Arrays.asList("ACTIVE","USED").contains(text(a,"state"))&&time(text(a,"expires_at")).isAfter(LocalDateTime.now()),"差价业务授权已撤销或过期，请核对原凭证");
        require(text(a,"platform_tid").equals(text(b,"platform_tid"))&&text(a,"platform_oid").equals(text(b,"platform_oid"))&&money(a.get("amount")).equals(money(p.get("refund_fee"))),"平台申请与差价授权的订单、子单或金额不一致");
        require(a.get("used_refund_id")==null||number(a,"used_refund_id")==number(b,"id"),"差价授权已被其他业务退款使用");
        workspace.review(c,number(b,"id"),m("control_version",b.get("control_version"),"platform_version",p.get("version"),"purpose","DIFFERENCE","goods_action","KEEP","reason","采用商家明确差价授权 "+a.get("reference_sn"),"evidence","授权 "+a.get("id")+"；登记人 "+a.get("authorized_by")+"；依据 "+a.get("evidence")),"自动售后规则");
        r().db().update("UPDATE oms_refund_money_authorization SET state='USED',used_refund_id=?,used_at=COALESCE(used_at,NOW()),control_version=control_version+1 WHERE company_code=? AND id=?",b.get("id"),c,a.get("id"));return true;
    }
}
