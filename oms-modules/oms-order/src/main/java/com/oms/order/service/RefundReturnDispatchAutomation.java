package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import com.ruoyi.common.core.constant.SecurityConstants;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** One dispatch policy for independent returns and confirmed shared parcels. */
@Service public class RefundReturnDispatchAutomation {
    @Resource RefundService refunds;
    @Resource RefundParcelPolicy policies;
    @Resource RefundGoodsService goods;
    @Resource RefundReturnClient returns;
    private OrderRepository r(){return refunds.repository;}

    public void process(String c,long refund){
        Map<String,Object> b=refunds.business(c,refund,false),policy=policies.get(c,number(b,"channel_id"));
        if(number(policy,"auto_dispatch")!=1)return;
        try{policies.catalog.validateShop(c,number(b,"channel_id"));}catch(RuntimeException unavailable){r().db().update("UPDATE oms_refund_return_plan SET auto_dispatch_state='WAIT_SHOP',auto_dispatch_message='店铺或店铺资料不可用，自动下发暂停',parcel_next_attempt=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE company_code=? AND refund_id=? AND state='PLANNED' AND auto_dispatch_hold=0 AND (parcel_lease_until IS NULL OR parcel_lease_until<NOW())",c,refund);return;}
        for(Map<String,Object> hint:r().db().query("SELECT * FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND state='PLANNED' AND auto_dispatch_state<>'FAILED' ORDER BY id LIMIT 100",ROW,c,refund)){
            long id=number(hint,"id");String lease=token();
            if(r().db().update("UPDATE oms_refund_return_plan SET parcel_lease_token=?,parcel_lease_until=DATE_ADD(NOW(),INTERVAL 90 SECOND) WHERE company_code=? AND id=? AND state='PLANNED' AND (parcel_lease_until IS NULL OR parcel_lease_until<NOW())",lease,c,id)!=1)continue;
            try{dispatch(c,refund,id,lease);}catch(IllegalArgumentException blocked){status(c,id,lease,"MANUAL",blocked.getMessage(),false);}
            catch(RuntimeException unavailable){Map<String,Object> p=plan(c,id);boolean exhausted=number(p,"auto_dispatch_attempts")>=4;status(c,id,lease,exhausted?"FAILED":"RETRY",exhausted?"自动下发多次未完成，请核对原仓库单后重新检查":"仓库服务暂不可用，稍后核对原单并重试",true);}
            finally{r().db().update("UPDATE oms_refund_return_plan SET parcel_next_attempt=DATE_ADD(NOW(),INTERVAL 60 SECOND),parcel_lease_token=NULL,parcel_lease_until=NULL WHERE company_code=? AND id=? AND parcel_lease_token=?",c,id,lease);}
        }
    }
    private Map<String,Object> plan(String c,long id){return r().db().query("SELECT * FROM oms_refund_return_plan WHERE company_code=? AND id=?",ROW,c,id).get(0);}
    @SuppressWarnings("unchecked") private void dispatch(String c,long refund,long id,String lease){
        Map<String,Object> p=plan(c,id),b=refunds.business(c,refund,false),policy=policies.get(c,number(b,"channel_id"));
        if(number(policy,"auto_dispatch")!=1)return;
        if(number(p,"auto_dispatch_hold")==1||number(p,"parcel_manual_dispatch")==1){status(c,id,lease,"HELD","已人工停止或指定人工下发，请在原安排中处理",false);return;}
        if(!"PLANNED".equals(text(p,"state"))||p.get("result_json")!=null)return;
        Map<String,Object> platform=refunds.platform(c,number(b,"platform_refund_id"),false);
        if(!"APPROVED".equals(text(b,"review_state"))||number(b,"approved_version")!=number(platform,"version")||!"VALID".equals(text(platform,"source_state"))||"CLOSED".equals(text(platform,"platform_status"))||"DONE".equals(text(b,"goods_state"))&&p.get("gift_plan_id")==null){status(c,id,lease,"WAIT_REVIEW","等待当前可信退款方案审核，或货物处理已结束",false);return;}
        require(RefundParcelPolicy.values(policy,"store_codes").contains(text(p,"store_code")),"退回仓未纳入自动下发范围，请核对店铺退货规则");
        if(Arrays.asList("WAIT_BUYER","WAREHOUSE_CHECK","SUPPLEMENTED").contains(text(p,"parcel_resolution"))||"NEED_CHECK".equals(text(p,"parcel_state"))||p.get("parcel_issue")!=null){status(c,id,lease,"MANUAL","退回包裹或实收归属待人工核对，暂不自动下发",false);return;}
        String mode=RefundParcelPolicy.mergeMode(policy);
        if(!"NONE".equals(mode)&&!"LINKED".equals(text(p,"parcel_state"))){status(c,id,lease,"AUTO".equals(mode)?"WAIT_LOGISTICS":"WAIT_ASSOCIATION","AUTO".equals(mode)?"等待可信退回物流与自动合包关联":"等待人工确认包裹范围；无需合包可在店铺规则选择不合包",false);return;}
        // Existing associations keep their confirmed scope even if a shop later disables merging.
        if("LINKED".equals(text(p,"parcel_state"))){
            Map<String,Object> box=null;for(Map<String,Object> candidate:data(returns.parcels(c,refund,SecurityConstants.INNER)))for(Map<String,Object> member:(List<Map<String,Object>>)candidate.get("members"))if(number(member,"plan_id")==id&&number(member,"active")!=0){box=candidate;break;}
            require(box!=null,"已有关联包裹，请刷新并核对原包裹范围");
            if(box.get("warehouse_document")!=null){status(c,id,lease,"SENT","已有仓库单，沿用原单查询进度",false);return;}
            if("AUTO".equals(mode)&&box.get("dispatch_after")!=null&&time(String.valueOf(box.get("dispatch_after")).replace('T',' ')).isAfter(java.time.LocalDateTime.now())){status(c,id,lease,"COLLECTING","正在等待合包归集结束",false);return;}
        }
        status(c,id,lease,"READY","退回安排已就绪，正在按店铺规则下发仓库",false);
        goods.action(c,refund,id,0,"dispatch",m("control_version",b.get("control_version"),"plan_version",p.get("control_version"),"automatic_dispatch",true),"自动退货下发");
        Map<String,Object> fresh=plan(c,id),actual=obj(fresh.get("result_json"));String state=text(actual,"dispatch_state");
        status(c,id,lease,"ACCEPTED".equals(state)?"SENT":"MANUAL","ACCEPTED".equals(state)?"仓库已接收退货单":"已保留原仓库单，请查询原单下发结果",false);
    }
    void status(String c,long plan,String lease,String state,String message,boolean failure){String safe=message==null?"请核对退回安排":message.substring(0,Math.min(500,message.length()));
        int changed=r().db().update("UPDATE oms_refund_return_plan SET auto_dispatch_state=?,auto_dispatch_message=?,auto_dispatch_attempts=auto_dispatch_attempts+? WHERE company_code=? AND id=? AND parcel_lease_token=? AND parcel_lease_until>NOW() AND (auto_dispatch_state<>? OR auto_dispatch_message<>? OR ?=1)",state,safe,failure?1:0,c,plan,lease,state,safe,failure?1:0);
        if(changed==1)refunds.event(c,number(plan(c,plan),"refund_id"),"RETURN_AUTO_DISPATCH",safe,"自动退货下发");
    }
}
