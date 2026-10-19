package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Trusted, current shop policy and refund priorities; no warehouse calls or inventory writes. */
@Service public class RefundAllocationContext {
    @Resource RefundService refunds;
    @Resource RefundParcelPolicy policy;
    @Resource OrderCatalog catalog;
    public Map<String,Object> context(String c,long channel,List<Long> plans){
        require(!plans.isEmpty()&&plans.size()<=100&&new HashSet<>(plans).size()==plans.size(),"退回来源范围无效");
        Map<String,Object> shop=catalog.channels(c).stream().filter(s->number(s,"channel_id")==channel).findFirst().orElseThrow(()->new IllegalArgumentException("店铺不属于当前公司"));
        List<Object> args=new ArrayList<>();args.add(c);args.add(channel);args.addAll(plans);
        List<Map<String,Object>> rows=refunds.repository.db().query("SELECT r.id plan_id,r.refund_id,r.quantity,r.accepted_quantity,r.waived_quantity,r.state plan_state,r.parcel_issue,b.review_state,b.approved_version,b.goods_state,b.create_time,b.channel_id,p.payload,p.version,p.source_state,p.platform_status FROM oms_refund_return_plan r JOIN oms_refund b ON b.company_code=r.company_code AND b.id=r.refund_id JOIN oms_platform_refund p ON p.company_code=b.company_code AND p.id=b.platform_refund_id WHERE r.company_code=? AND b.channel_id=? AND r.id IN ("+String.join(",",Collections.nCopies(plans.size(),"?"))+")",ROW,args.toArray());
        require(rows.size()==plans.size(),"退回来源不属于当前店铺或公司");List<Map<String,Object>> priorities=new ArrayList<>();
        for(Map<String,Object> row:rows){String created="",basis="PLATFORM_CREATED";try{created=text(RefundValues.parse(text(row,"payload")),"created");if(!created.isEmpty())time(created);}catch(RuntimeException invalid){created="";}
            if(created.isEmpty()){created=text(row,"create_time").replace('T',' ');basis="OMS_CREATED_FALLBACK";}
            boolean eligible=number(shop,"enabled")==1&&"APPROVED".equals(text(row,"review_state"))&&number(row,"approved_version")==number(row,"version")&&"VALID".equals(text(row,"source_state"))&&!"CLOSED".equals(text(row,"platform_status"))&&!"DONE".equals(text(row,"goods_state"))&&!Arrays.asList("CLOSED","RELEASED","STOPPED").contains(text(row,"plan_state"))&&number(row,"waived_quantity")==0&&row.get("parcel_issue")==null;
            priorities.add(m("plan_id",row.get("plan_id"),"refund_id",row.get("refund_id"),"refund_created",created,"priority_basis",basis,"eligible",eligible));}
        return m("policy",policy.get(c,channel),"priorities",priorities);
    }
}
