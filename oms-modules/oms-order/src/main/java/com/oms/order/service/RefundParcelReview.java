package com.oms.order.service;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Human decisions preserve old shipment and receipt facts; they never imply inventory posting. */
@Service public class RefundParcelReview {
    @Resource RefundService refunds;
    @Resource RefundParcelWorkspace parcels;
    @Resource RefundGoodsService goods;
    @Resource RefundReturnClient returns;
    private Map<String,Object> plan(String c,long refund,long plan,boolean lock){List<Map<String,Object>> rows=refunds.repository.db().query("SELECT * FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND id=?"+(lock?" FOR UPDATE":""),ROW,c,refund,plan);require(rows.size()==1,"退回计划不存在或不属于本退款");return rows.get(0);}
    public Map<String,Object> context(String c,long refund,long plan){Map<String,Object> b=refunds.business(c,refund,false),p=plan(c,refund,plan,false);require(p.get("parcel_issue")!=null,"合包问题已变化，请刷新详情");Map<String,Object> issue=obj(p.get("parcel_issue"));Map<String,Object> target=number(issue,"target_parcel_id")==0?Collections.emptyMap():data(returns.inspectParcel(c,number(issue,"target_parcel_id"),number(b,"channel_id"),text(b,"platform_tid"),SecurityConstants.INNER));return m("issue",issue,"target",target,"plan",m("id",plan,"quantity",p.get("quantity"),"accepted_quantity",p.get("accepted_quantity"),"posted_quantity",p.get("posted_quantity"),"state",p.get("state"),"version",p.get("control_version"),"has_document",p.get("result_json")!=null),"control_version",b.get("control_version"));}
    public void decide(String c,long refund,long plan,Map<String,Object> input,String actor){String decision=required(input,"decision",32),reason=required(input,"reason",500),evidence=required(input,"evidence",1000);require(Boolean.TRUE.equals(input.get("confirmed")),"请确认已核实实际包裹和商品归属");require(Arrays.asList("CORRECT_LOGISTICS","WAIT_BUYER","WAREHOUSE_CHECK").contains(decision),"请选择明确的核对结论");
        Map<String,Object> current=refunds.tx().execute(tx->{Map<String,Object> b=refunds.business(c,refund,true),p=plan(c,refund,plan,true);Map<String,Object> platform=refunds.platform(c,number(b,"platform_refund_id"),true);require(number(input,"control_version")==number(b,"control_version")&&number(input,"plan_version")==number(p,"control_version"),"退款或退回计划已变化，请刷新后核对");require("APPROVED".equals(text(b,"review_state"))&&number(b,"approved_version")==number(platform,"version")&&"VALID".equals(text(platform,"source_state"))&&!"CLOSED".equals(text(platform,"platform_status"))&&!"DONE".equals(text(b,"goods_state")),"请先审核当前可信平台方案，关闭或完成的退款不能新增退回范围");require(p.get("parcel_issue")!=null&&p.get("result_json")==null&&"PLANNED".equals(text(p,"state")),"本问题已处理或已有THK，请刷新后核对原单");require((p.get("lease_until")==null||!time(text(p,"lease_until")).isAfter(java.time.LocalDateTime.now()))&&(p.get("parcel_lease_until")==null||!time(text(p,"parcel_lease_until")).isAfter(java.time.LocalDateTime.now())),"退回计划正在处理，请稍后核对");goods.verifyParcelScope(c,b,p);return p;});
        Map<String,Object> issue=obj(current.get("parcel_issue"));
        final String resolution="WAREHOUSE_CHECK".equals(decision)?"WAREHOUSE_CHECK":"WAIT_BUYER".equals(decision)?"WAIT_BUYER":"";
        final String carrier="CORRECT_LOGISTICS".equals(decision)?required(input,"carrier_code",50).toUpperCase(Locale.ROOT):"",waybill="CORRECT_LOGISTICS".equals(decision)?required(input,"waybill",50):"";
        if("CORRECT_LOGISTICS".equals(decision))require(carrier.matches("[A-Z0-9_-]{1,50}")&&waybill.matches("[A-Za-z0-9_-]{1,50}")&&(!carrier.equals(text(issue,"carrier_code"))||!waybill.equals(text(issue,"waybill"))),"请填写核实后的不同寄回物流；原包裹包含商品请转仓库差异核对");
        refunds.tx().execute(tx->{Map<String,Object> b=refunds.business(c,refund,true),p=plan(c,refund,plan,true);require(number(b,"control_version")==number(input,"control_version")&&number(p,"control_version")==number(input,"plan_version")&&(p.get("lease_until")==null||!time(text(p,"lease_until")).isAfter(java.time.LocalDateTime.now())),"核对期间单据已变化，请刷新");
            if("WAREHOUSE_CHECK".equals(decision)){Map<String,Object> next=new LinkedHashMap<>(issue);next.put("title","合包追加待仓库核对");next.put("next_action","等待仓库核实包裹商品、提供共同THK改单或差异处置凭证，再继续核对");refunds.repository.db().update("UPDATE oms_refund_return_plan SET parcel_resolution=?,parcel_issue=?,parcel_state='NEED_CHECK',parcel_error='等待仓库核实实物与单据差异',control_version=control_version+1 WHERE company_code=? AND id=?",resolution,encode(next),c,plan);}
            else refunds.repository.db().update("UPDATE oms_refund_return_plan SET parcel_resolution=?,return_carrier=?,return_waybill=?,logistics_source='MANUAL',transport_evidence=?,parcel_issue=NULL,parcel_state='WAIT_LOGISTICS',parcel_error=?,control_version=control_version+1 WHERE company_code=? AND id=?",resolution,carrier,waybill,evidence,"WAIT_BUYER".equals(decision)?"已确认本退款商品尚未寄回；等待实际寄回物流":"已更正寄回物流，下一步登记独立包裹或按配置自动关联",c,plan);
            refunds.event(c,refund,"PARCEL_REVIEW","合包归属核对："+text(m("WAREHOUSE_CHECK","转仓库差异核对","WAIT_BUYER","尚未寄回，等待买家物流","CORRECT_LOGISTICS","更正寄回物流"),decision)+"；依据："+reason+"；凭证："+evidence+"；未增加库存",actor);return null;});
    }
}
