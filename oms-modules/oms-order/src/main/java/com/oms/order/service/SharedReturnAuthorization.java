package com.oms.order.service;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.map;

/** Current shop authorization is fetched before physical writes and receipt allocation. */
@Service public class SharedReturnAuthorization {
    @Resource OrderRepository repository;
    @Resource ExchangeFlowPolicy exchanges;
    @Resource RefundParcelPolicy refunds;
    public Map<String,Object> context(String c,List<Map<String,Object>> members){
        require(members!=null&&!members.isEmpty()&&members.size()<=100,"共同退回来源数量无效");
        Map<String,Map<String,Object>> byKind=new HashMap<>();List<Map<String,Object>> rows=new ArrayList<>();
        Map<String,Map<Long,Map<String,Object>>> originals=new HashMap<>();
        for(String kind:Arrays.asList("EXCHANGE","REFUND")){
            Set<Long> ids=new LinkedHashSet<>();for(Map<String,Object> m:members){require(Arrays.asList("EXCHANGE","REFUND").contains(text(m,"owner_kind")),"售后来源类型无效");if(kind.equals(text(m,"owner_kind")))ids.add(number(m,"source_id"));}
            Map<Long,Map<String,Object>> indexed=new HashMap<>();originals.put(kind,indexed);if(ids.isEmpty())continue;
            String marks=String.join(",",Collections.nCopies(ids.size(),"?"));List<Object> args=new ArrayList<>();args.add(c);args.addAll(ids);
            String query="EXCHANGE".equals(kind)?"SELECT id source_id,id,channel_id,platform_tid,platform_oid,exchange_sn owner_sn,approved_version,control_version,platform_status,state,fulfillment_hold FROM oms_exchange WHERE company_code=? AND id IN ("+marks+")":"SELECT p.id source_id,b.id,b.channel_id,b.platform_tid,b.platform_oid,b.refund_sn owner_sn,b.review_state,b.approved_version,f.version platform_version,f.source_state,f.platform_status,p.state FROM oms_refund_return_plan p JOIN oms_refund b ON b.company_code=p.company_code AND b.id=p.refund_id JOIN oms_platform_refund f ON f.company_code=b.company_code AND f.id=b.platform_refund_id WHERE p.company_code=? AND p.id IN ("+marks+")";
            for(Map<String,Object> row:repository.db().query(query,ROW,args.toArray()))indexed.put(number(row,"source_id"),row);
        }
        long channel=0;String tid="";
        for(Map<String,Object> m:members){
            String kind=text(m,"owner_kind");Map<String,Object> b=originals.get(kind).get(number(m,"source_id"));require(b!=null,"共同退回的原业务来源不存在");
            require(number(m,"owner_id")==number(b,"id")&&text(m,"owner_sn").equals(text(b,"owner_sn"))&&text(m,"platform_oid").equals(text(b,"platform_oid")),"共同退回业务归属与原单不一致");
            if(channel==0){channel=number(b,"channel_id");tid=text(b,"platform_tid");}
            require(channel==number(b,"channel_id")&&tid.equals(text(b,"platform_tid")),"共同退回不能跨店铺或原平台订单");
            Map<String,Object> policy=byKind.get(kind);if(policy==null){policy="EXCHANGE".equals(kind)?exchanges.read(c,channel):refunds.get(c,channel);byKind.put(kind,policy);}
            boolean active="EXCHANGE".equals(kind)?b.get("approved_version")!=null&&number(b,"fulfillment_hold")==0&&!Arrays.asList("4","14").contains(text(b,"platform_status"))&&!Arrays.asList("CANCELED","CLOSED","REJECTED").contains(text(b,"state")):"APPROVED".equals(text(b,"review_state"))&&number(b,"approved_version")==number(b,"platform_version")&&"VALID".equals(text(b,"source_state"))&&!"CLOSED".equals(text(b,"platform_status"))&&!Arrays.asList("CLOSED","STOPPED","RELEASED").contains(text(b,"state"));
            boolean enabled=active&&number(policy,"enabled")==1&&("EXCHANGE".equals(kind)?number(policy,"auto_return_merge")==1:number(policy,"auto_enabled")==1&&number(policy,"allow_exchange_merge")==1);
            rows.add(map("member_id",m.get("id"),"active",active,"revision",policy.get("revision"),"dispatch",enabled&&number(policy,"EXCHANGE".equals(kind)?"auto_return_dispatch":"auto_dispatch")==1,"allocate",enabled&&("EXCHANGE".equals(kind)?number(policy,"auto_allocate_receipt")==1:!"MANUAL".equals(text(policy,"allocation_mode"))),"mixed",enabled&&number(policy,"EXCHANGE".equals(kind)?"auto_mixed_return_merge":"allow_exchange_merge")==1));
        }
        return map("channel_id",channel,"platform_tid",tid,"members",rows);
    }
}
