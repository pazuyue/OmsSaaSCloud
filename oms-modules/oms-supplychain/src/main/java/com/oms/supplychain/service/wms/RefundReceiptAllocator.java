package com.oms.supplychain.service.wms;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Resolve current business policy outside inventory transactions; fence application by parcel version. */
@Service public class RefundReceiptAllocator {
    @Resource WmsStore db;
    @Resource RefundAllocationClient orders;
    @SuppressWarnings("unchecked") private Map<String,Object> object(Object value){try{return value instanceof Map?(Map<String,Object>)value:db.json.readValue(text(value),Map.class);}catch(Exception e){throw new IllegalArgumentException("分配来源快照不可读");}}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    private long n(Object v){return v==null?0:id(v);}
    private Map<String,Object> receipt(String c,long event){return db.one("SELECT * FROM wms_refund_parcel_receipt WHERE company_code=? AND id=?",c,event);}
    private Map<String,Object> context(String c,long parcel){
        List<Map<String,Object>> sources=db.jdbc.queryForList("SELECT DISTINCT plan_id,channel_id FROM wms_refund_parcel_member WHERE company_code=? AND parcel_id=? AND active=1 ORDER BY plan_id",c,parcel);
        require(!sources.isEmpty()&&sources.size()<=100,"包裹来源数量无效");long channel=id(sources.get(0).get("channel_id"));List<Long> plans=new ArrayList<>();
        for(Map<String,Object> source:sources){require(id(source.get("channel_id"))==channel,"共同THK存在跨店铺来源");plans.add(id(source.get("plan_id")));}
        R<Map<String,Object>> reply=orders.context(c,channel,plans,SecurityConstants.INNER);require(reply!=null&&R.isSuccess(reply)&&reply.getData()!=null,"实收分配规则暂不可用");return reply.getData();
    }
    public Map<String,Object> evaluate(String c,long refund,long parcel,long event,Map<String,Object> input,RefundParcels parcels,RefundReturnService returns){
        require(db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_refund_parcel_member WHERE company_code=? AND parcel_id=? AND refund_id=?",Long.class,c,parcel,refund)>0,"包裹不属于当前退款单");
        Map<String,Object> e=receipt(c,event);require(n(e.get("parcel_id"))==parcel,"回传不属于所选包裹");Map<String,Object> decision=decide(c,event,context(c,parcel));
        if(Boolean.TRUE.equals(input.get("execute"))){require(Boolean.TRUE.equals(input.get("confirmed")),"请确认按当前规则分配并入账");require(n(input.get("parcel_version"))==n(decision.get("parcel_version"))&&n(input.get("policy_revision"))==n(decision.get("policy_revision")),"包裹或规则版本已变化，请重新预览");apply(c,event,decision,parcels,returns);}
        return decision;
    }
    public void attempt(String c,long event,RefundParcels parcels,RefundReturnService returns){
        if(!"WAIT_ALLOCATION".equals(receipt(c,event).get("state")))return;
        try{Map<String,Object> decision=decide(c,event,context(c,n(receipt(c,event).get("parcel_id"))));if("READY".equals(decision.get("status")))apply(c,event,decision,parcels,returns);else record(c,event,decision,false);}
        catch(RuntimeException failure){record(c,event,map("status","RETRY","reason","自动分配未完成，原实收保留；后台将重试，也可按当前规则重新预览"),true);}
    }
    public void reconcile(String c,RefundParcels parcels,RefundReturnService returns){
        for(Long event:db.jdbc.queryForList("SELECT id FROM wms_refund_parcel_receipt WHERE company_code=? AND state='WAIT_ALLOCATION' AND (allocation_metadata IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(allocation_metadata,'$.status'))='RETRY') AND (auto_after IS NULL OR auto_after<=NOW()) ORDER BY id LIMIT 10",Long.class,c))attempt(c,event,parcels,returns);
    }
    private void record(String c,long event,Map<String,Object> decision,boolean retry){db.jdbc.update("UPDATE wms_refund_parcel_receipt SET allocation_metadata=?,auto_after=IF(?=1,DATE_ADD(NOW(),INTERVAL 30 SECOND),NULL) WHERE company_code=? AND id=? AND state='WAIT_ALLOCATION'",db.encode(decision),retry?1:0,c,event);}
    private void apply(String c,long event,Map<String,Object> decision,RefundParcels parcels,RefundReturnService returns){
        require("READY".equals(decision.get("status")),text(decision.get("reason")));Map<String,Object> e=receipt(c,event);
        Map<String,Object> input=map("decision","ALLOCATE","confirmed",true,"actor","自动实收分配","reason",decision.get("reason"),"evidence","原仓库消息 "+e.get("message_id")+"；规则版本 "+decision.get("policy_revision"),"allocations",decision.get("allocations"));
        parcels.allocateAutomatic(c,n(decision.get("refund_id")),n(e.get("parcel_id")),event,input,decision,returns);
    }
    private Map<String,Object> decide(String c,long event,Map<String,Object> context){
        return db.tx().execute(tx->{Map<String,Object> initial=receipt(c,event);long parcel=n(initial.get("parcel_id"));Map<String,Object> p=db.one("SELECT * FROM wms_refund_parcel WHERE company_code=? AND id=? FOR UPDATE",c,parcel);Map<String,Object> e=receipt(c,event),policy=object(context.get("policy"));
            Map<String,Object> result=map("status","MANUAL","parcel_version",p.get("control_version"),"policy_revision",policy.get("revision"),"policy",map("allocation_mode",policy.get("allocation_mode"),"allow_partial_allocation",policy.get("allow_partial_allocation"),"allocation_order",policy.get("allocation_order")),"allocations",new ArrayList<>(),"rows",new ArrayList<>());
            try{
                require("WAIT_ALLOCATION".equals(e.get("state")),"本次回传已经处理，请刷新");require(!"MANUAL".equals(policy.get("allocation_mode")),"店铺设置为人工确认实收");
                Map<String,Object> d=db.one("SELECT * FROM wms_refund_parcel_document WHERE company_code=? AND id=?",c,e.get("document_id"));require("NONE".equals(d.get("cancel_state"))&&"ACCEPTED".equals(d.get("dispatch_state")),"涉及截单或仓库受理冲突，须人工核对");
                List<Map<String,Object>> scope=db.json.readValue(text(d.get("payload")),List.class);Set<Long> taskIds=new HashSet<>();for(Map<String,Object> line:scope)taskIds.add(n(line.get("task_id")));
                List<Map<String,Object>> sources=db.jdbc.queryForList("SELECT m.*,t.unit_cost,t.original_shipment_sn,t.store_code task_store,t.owner_code task_owner,t.accepted_quantity,t.posted_quantity,t.dispatch_state,t.cancel_state FROM wms_refund_parcel_member m JOIN wms_refund_return t ON t.company_code=m.company_code AND t.id=m.task_id WHERE m.company_code=? AND m.parcel_id=? AND m.active=1 ORDER BY m.id",c,parcel);
                sources.removeIf(m->!taskIds.contains(n(m.get("task_id"))));require(!sources.isEmpty(),"原THK来源明细缺失");
                Map<Long,Map<String,Object>> priority=new HashMap<>();for(Map<String,Object> row:list(context.get("priorities")))priority.put(n(row.get("plan_id")),row);
                sources.sort(Comparator.comparing((Map<String,Object> m)->text(priority.getOrDefault(n(m.get("plan_id")),Collections.emptyMap()).get("refund_created"))).thenComparingLong(m->n(m.get("refund_id"))).thenComparingLong(m->n(m.get("plan_id"))).thenComparingLong(m->n(m.get("id"))));
                WmsProtocol.Receipt raw=db.json.readValue(text(e.get("payload")),WmsProtocol.Receipt.class);long remaining=sources.stream().mapToLong(m->n(m.get("quantity"))-n(m.get("accepted_quantity"))).sum();
                boolean partial="RULE".equals(policy.get("allocation_mode"))&&n(policy.get("allow_partial_allocation"))==1;
                require(!raw.complete||n(e.get("quantity"))==remaining||partial,"最终实收与待收数量不一致，须核对短收或超收");
                List<Map<String,Object>> allocations=new ArrayList<>();Map<Long,Long> used=new HashMap<>();boolean ruleUsed=false;
                for(int index=0;index<raw.lines.size();index++){
                    WmsProtocol.Line line=raw.lines.get(index);long qty=(long)line.good+line.bad;require(qty>0&&line.good>=0&&line.bad>=0,"实收数量无效");require(line.owner.isEmpty()||line.owner.equals(text(p.get("external_owner"))),"回传明细货主不一致");
                    List<Map<String,Object>> matches=new ArrayList<>();for(Map<String,Object> m:sources)if(line.sku.equals(text(m.get("sku_sn")))&&(line.lineNo.isEmpty()||line.lineNo.equals(text(m.get("id"))))&&n(m.get("quantity"))>n(m.get("accepted_quantity"))+used.getOrDefault(n(m.get("task_id")),0L))matches.add(m);
                    require(!matches.isEmpty(),"SKU或明细行号没有有效待收来源，不能自动分配");
                    for(Map<String,Object> m:matches){Map<String,Object> pr=priority.get(n(m.get("plan_id")));require(pr!=null&&Boolean.TRUE.equals(pr.get("eligible"))&&!text(pr.get("refund_created")).isEmpty(),"退款方案、来源或申请时间尚未核对，不能自动分配");require("ACCEPTED".equals(m.get("dispatch_state"))&&"NONE".equals(m.get("cancel_state")),"原THK来源已截停或尚未受理");}
                    long available=matches.stream().mapToLong(m->n(m.get("quantity"))-n(m.get("accepted_quantity"))-used.getOrDefault(n(m.get("task_id")),0L)).sum();require(qty<=available,"回传数量超过原来源剩余待收数量");
                    if(matches.size()>1){require(raw.lines.stream().filter(l->line.sku.equals(l.sku)).count()==1,"同SKU有多行批次回传，须核对来源对应关系");require(line.good==0||line.bad==0,"同SKU存在良次品混合，须人工分配品质");
                        if(qty<available){require(partial,"同SKU部分实收缺少来源明细行号，店铺未开启部分实收自动分配");Map<String,Object> first=matches.get(0);require(n(first.get("source_line_id"))>0&&!text(first.get("original_shipment_sn")).isEmpty(),"原发货来源缺失，不能按顺序分摊");for(Map<String,Object> m:matches)require(n(m.get("source_line_id"))==n(first.get("source_line_id"))&&text(m.get("original_shipment_sn")).equals(text(first.get("original_shipment_sn")))&&text(m.get("task_store")).equals(text(first.get("task_store")))&&text(m.get("task_owner")).equals(text(first.get("task_owner")))&&new BigDecimal(text(m.get("unit_cost"))).compareTo(new BigDecimal(text(first.get("unit_cost"))))==0,"原发货来源、接收仓、货主或成本存在差异，不能按顺序分摊");ruleUsed=true;}}
                    long left=qty;for(Map<String,Object> m:matches){long task=n(m.get("task_id")),take=Math.min(left,n(m.get("quantity"))-n(m.get("accepted_quantity"))-used.getOrDefault(task,0L));if(take<=0)continue;
                        allocations.add(map("line_index",index,"task_id",task,"good_qty",matches.size()==1?line.good:line.good>0?take:0,"bad_qty",matches.size()==1?line.bad:line.bad>0?take:0));used.merge(task,take,Long::sum);left-=take;if(left==0)break;}
                    require(left==0,"原实收数量未能完整分配");
                }
                require(!allocations.isEmpty(),"原回传没有可分配实收");List<Map<String,Object>> rows=new ArrayList<>();for(Map<String,Object> m:sources){Map<String,Object> pr=priority.getOrDefault(n(m.get("plan_id")),Collections.emptyMap());rows.add(map("refund_id",m.get("refund_id"),"refund_sn",m.get("refund_sn"),"plan_id",m.get("plan_id"),"task_id",m.get("task_id"),"quantity",m.get("quantity"),"assigned",used.getOrDefault(n(m.get("task_id")),0L),"remaining",n(m.get("quantity"))-n(m.get("accepted_quantity"))-used.getOrDefault(n(m.get("task_id")),0L),"refund_created",pr.get("refund_created"),"priority_basis",pr.get("priority_basis")));}
                result.putAll(map("status","READY","method",ruleUsed?"RULE_PARTIAL":"SOURCE_MATCH","reason",ruleUsed?"同SKU部分实收，按退款申请时间优先；同时间按TK及来源顺序分配":"按仓库明细行、唯一来源或完整数量匹配分配","allocations",allocations,"rows",rows,"refund_id",sources.get(0).get("refund_id"),"warehouse_complete",raw.complete,"short_quantity",Math.max(0,remaining-n(e.get("quantity")))));
            }catch(Exception blocked){result.put("reason",blocked instanceof IllegalArgumentException?text(blocked.getMessage()):"原回传或来源快照无法核对，请人工处理");}
            return result;
        });
    }
}
