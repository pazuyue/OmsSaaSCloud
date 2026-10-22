package com.oms.order.service;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
/** Shared-document preflight has no dependency on either UI workspace or goods executor. */
@Service public class RefundSharedScope {
    @Resource RefundService refunds;
    @Resource RefundReturnClient returns;
    @SuppressWarnings("unchecked") Map<String,Object> validate(String c,long refund,long plan){return validate(c,refund,plan,false);}
    Map<String,Object> validate(String c,long refund,long plan,boolean rebuild){
        for(Map<String,Object> p:data(returns.parcels(c,refund,SecurityConstants.INNER)))if(members(p).stream().anyMatch(m->number(m,"plan_id")==plan)){
            for(Map<String,Object> m:members(p)){if(number(m,"active")==0||number(m,"quantity")==number(m,"accepted_quantity"))continue;Map<String,Object> b=refunds.business(c,number(m,"refund_id"),false),platform=refunds.platform(c,number(b,"platform_refund_id"),false);require("APPROVED".equals(text(b,"review_state"))&&number(b,"approved_version")==number(platform,"version")&&"VALID".equals(text(platform,"source_state"))&&!"CLOSED".equals(text(platform,"platform_status"))&&(!"DONE".equals(text(b,"goods_state"))||refunds.repository.db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND id=? AND gift_plan_id IS NOT NULL",Long.class,c,b.get("id"),m.get("plan_id"))==1),"共同THK中有退款方案变化或未审核，请核对全部来源");Map<String,Object> source=refunds.repository.db().query("SELECT * FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND id=?",ROW,c,m.get("refund_id"),m.get("plan_id")).get(0);require(!Arrays.asList("CLOSED","RELEASED").contains(text(source,"state"))&&(rebuild||!"STOPPED".equals(text(source,"state")))&&number(source,"waived_quantity")==0&&source.get("parcel_issue")==null,"共同THK中有计划已停止或存在合包问题");verifySource(c,b,source);}
            return p;
        }return null;
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> members(Map<String,Object> p){return (List<Map<String,Object>>)p.get("members");}
    private void verifySource(String c,Map<String,Object> b,Map<String,Object> p){List<Map<String,Object>> rows=refunds.repository.db().query("SELECT sl.id,sl.shipped_quantity,sl.order_line_id,sl.stock_source_id,ol.gift_plan_id FROM oms_order_shipment_line sl JOIN oms_order_shipment sh ON sh.company_code=sl.company_code AND sh.id=sl.shipment_id JOIN oms_order_line ol ON ol.company_code=sl.company_code AND ol.id=sl.order_line_id WHERE sl.company_code=? AND sh.order_id=? AND (ol.platform_oid=? OR ol.gift_plan_id IS NOT NULL) AND sl.id=?",ROW,c,b.get("order_id"),b.get("platform_oid"),p.get("shipment_line_id"));require(rows.size()==1,"退回来源不属于本平台子单");Map<String,Object> source=rows.get(0);if(p.get("gift_plan_id")!=null)refunds.repository.gifts().validateReturn(c,b,source,number(p,"quantity"),number(p,"id"));else require(source.get("gift_plan_id")==null,"正品安排不能引用赠品来源");long available=number(source,"shipped_quantity")-ReturnSourceClaims.refundUsed(refunds.repository,c,number(source,"id"),number(p,"id"))-ReturnSourceClaims.exchangeUsed(refunds.repository,c,number(source,"id"));require(number(p,"waived_quantity")==0&&number(p,"quantity")<=available,"原出库来源额度变化或已不再追回，请核对全部来源");}
}
