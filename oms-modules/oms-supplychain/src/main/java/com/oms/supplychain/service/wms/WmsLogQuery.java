package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Read-only local projections. Never sends requests to a warehouse. */
@Service
public class WmsLogQuery {
    @Resource private WmsStore db;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String OUTBOUND="l.action IN ('OUTBOUND_CREATE','OUTBOUND_QUERY','OUTBOUND_CANCEL','OUTBOUND_RECEIPT','OUTBOUND_REPAIR')";
    private static final String INBOUND="l.action IN ('CREATE','QUERY','CANCEL','RECEIPT')";
    private static final String RETURN="l.action IN ('RETURN_CREATE','RETURN_QUERY','RETURN_RECEIPT','RETURN_CANCEL','RETURN_REVIEW')";
    private static final String REFUND="l.action IN ('REFUND_RETURN_CREATE','REFUND_RETURN_QUERY','REFUND_RETURN_RECEIPT','REFUND_RETURN_CANCEL','REFUND_RETURN_REVIEW')";
    private static final String PARCEL="l.action IN ('REFUND_PARCEL_RECEIPT','REFUND_PARCEL_REVIEW','REFUND_PARCEL_LINK_REVIEW')";
    private static final String KIND="CASE WHEN "+OUTBOUND+" THEN IF(ot.business_type='EXCHANGE','EXCHANGE_OUT','SALE_OUT') WHEN "+INBOUND+" THEN 'PURCHASE_IN' WHEN "+REFUND+" THEN 'REFUND_IN' WHEN "+PARCEL+" THEN 'REFUND_IN' WHEN "+RETURN+" THEN 'EXCHANGE_IN' ELSE 'OTHER' END";
    private static final String FROM=" FROM wms_interaction_log l LEFT JOIN wms_connection c ON c.company_code=l.company_code AND c.id=l.connection_id"
        +" LEFT JOIN wms_outbound_task ot ON ot.company_code=l.company_code AND ot.id=l.task_id AND "+OUTBOUND
        +" LEFT JOIN wms_exchange_return rt ON rt.company_code=l.company_code AND rt.id=l.task_id AND "+RETURN
        +" LEFT JOIN wms_refund_parcel_document pd ON pd.company_code=l.company_code AND pd.return_sn=l.ticket_sn AND "+REFUND
        +" LEFT JOIN wms_refund_parcel rp ON rp.company_code=l.company_code AND ((rp.parcel_sn=l.ticket_sn AND "+PARCEL+") OR rp.id=pd.parcel_id)"
        +" LEFT JOIN wms_refund_return fr ON fr.company_code=l.company_code AND fr.id=l.task_id AND "+REFUND
        +" LEFT JOIN wms_inbound_task it ON it.company_code=l.company_code AND it.id=l.task_id AND "+INBOUND
        +" LEFT JOIN wms_tickets t ON UPPER(t.company_code)=l.company_code AND t.id=it.ticket_id"
        +" LEFT JOIN no_tickets arrival ON UPPER(arrival.company_code)=l.company_code AND arrival.no_sn=t.relation_sn"
        +" LEFT JOIN wms_simulation_store_info v ON UPPER(v.company_code)=l.company_code AND v.wms_simulation_code=t.wms_simulation_code"
        +" LEFT JOIN owner_warehouse ow ON UPPER(ow.company_code)=l.company_code AND ow.id=v.owner_warehouse_id"
        +" LEFT JOIN owner_info oi ON UPPER(oi.company_code)=l.company_code AND oi.id=ow.owner_id"
        +" LEFT JOIN wms_real_store_info rw ON UPPER(rw.company_code)=l.company_code AND rw.id=ow.real_store_id";
    private static final String SELECT="SELECT l.id,l.connection_id,l.task_id,l.ticket_sn,l.direction,l.action,l.request_id,l.result,l.error_info,l.result_summary,l.duration_ms,l.create_time,"
        +"c.name connection_name,c.provider,c.environment,"+KIND+" business_type,ot.shipment_id,COALESCE(it.ticket_id,rt.ticket_id,fr.ticket_id,pd.ticket_id) ticket_id,ot.order_id,COALESCE(ot.order_sn,rt.order_sn,fr.order_sn) order_sn,COALESCE(ot.platform_tid,rt.platform_tid,fr.platform_tid) platform_tid,COALESCE(ot.exchange_id,rt.exchange_id) exchange_id,COALESCE(NULLIF(ot.business_owner_sn,''),rt.exchange_sn,fr.refund_sn) business_owner_sn,fr.refund_id,rp.id parcel_id,rp.parcel_sn,pd.id shared_document_id,"
        +"COALESCE(ot.external_order,it.external_order,rt.external_order,fr.external_order,pd.external_order,'') external_order,COALESCE(ot.external_warehouse,it.external_warehouse,rt.external_warehouse,fr.external_warehouse,rp.external_warehouse,'') external_warehouse,"
        +"COALESCE(ot.external_owner,it.external_owner,rt.external_owner,fr.external_owner,rp.external_owner,'') external_owner,COALESCE(ot.real_store_code,rt.real_store_code,fr.real_store_code,rp.real_store_code,rw.real_store_code,'') real_store_code,"
        +"COALESCE(ot.owner_code,rt.owner_code,fr.owner_code,rp.owner_code,oi.owner_code,'') owner_code,rw.wms_name real_store_name,oi.owner_name,"
        +"COALESCE(ot.dispatch_state,it.dispatch_state,rt.dispatch_state,fr.dispatch_state,pd.dispatch_state,'') dispatch_state,COALESCE(ot.warehouse_status,it.warehouse_status,rt.warehouse_status,fr.warehouse_status,pd.warehouse_status,'') warehouse_status,"
        +"COALESCE(ot.cancel_state,rt.cancel_state,fr.cancel_state,pd.cancel_state,'') cancel_state,it.cancel_state inbound_cancel_state,COALESCE(it.receipt_state,rt.receipt_state,fr.receipt_state) receipt_state,t.inventory_status,arrival.id receipt_id,arrival.no_sn receipt_sn,t.original_sn purchase_sn,arrival.cutoff_state inbound_cutoff_state";

    private static class Filter { String sql=" WHERE l.company_code=?"; List<Object> args=new ArrayList<>(); }
    private void add(Filter f,String sql,Object value){f.sql+=" AND "+sql;f.args.add(value);}
    private String field(Map<String,String> q,String key,int max){String s=text(q.get(key));require(s.length()<=max,"查询条件过长："+key);return s;}
    private void choice(Filter f,Map<String,String> q,String key,String column,String... allowed){String s=field(q,key,64);if(!s.isEmpty()){require(Arrays.asList(allowed).contains(s),"查询条件无效："+key);add(f,column+"=?",s);}}
    private String timestamp(Object value){if(value instanceof java.sql.Timestamp)return ((java.sql.Timestamp)value).toLocalDateTime().format(TIME);if(value instanceof LocalDateTime)return ((LocalDateTime)value).format(TIME);String s=text(value).replace('T',' ');return time(s.length()==16?s+":00":s.substring(0,19)).format(TIME);}
    private LocalDateTime time(String value){try{return LocalDateTime.parse(value,TIME);}catch(Exception e){throw new IllegalArgumentException("请选择有效的查询时间");}}
    private Filter filter(String company,Map<String,String> q){
        Filter f=new Filter();f.args.add(company);String number=field(q,"number",128),type=field(q,"numberType",32);boolean all="true".equals(q.get("allTime"));
        require(!all||!number.isEmpty(),"查询全部时间必须提供完整单号或请求编号");
        if(!number.isEmpty()){
            require(Arrays.asList("DOCUMENT","ORDER","PLATFORM","WAREHOUSE","REQUEST","ARRIVAL","PURCHASE","EXCHANGE","REFUND").contains(type),"请选择单号类型");
            if("DOCUMENT".equals(type)||"REQUEST".equals(type))add(f,("DOCUMENT".equals(type)?"l.ticket_sn":"l.request_id")+"=?",number);
            else documentTasks(f,company,type,number);
        }
        if(!all){LocalDateTime end=q.containsKey("end")&&!text(q.get("end")).isEmpty()?time(q.get("end")):LocalDateTime.now().withNano(0);LocalDateTime begin=q.containsKey("begin")&&!text(q.get("begin")).isEmpty()?time(q.get("begin")):end.minusDays(1);require(begin.isBefore(end)&&Duration.between(begin,end).compareTo(Duration.ofDays(31))<=0,"查询时间范围须大于0且不超过31天");add(f,"l.create_time>=?",begin.format(TIME));add(f,"l.create_time<=?",end.format(TIME));}
        choice(f,q,"businessType",KIND,"SALE_OUT","PURCHASE_IN","SALE_RETURN","EXCHANGE_IN","EXCHANGE_OUT","REFUND_IN","OTHER");choice(f,q,"direction","l.direction","IN","OUT","LOCAL");choice(f,q,"result","l.result","STARTED","SUCCESS","FAILED","UNKNOWN");
        choice(f,q,"environment","c.environment","TEST","PRODUCTION");choice(f,q,"action","l.action","OUTBOUND_CREATE","OUTBOUND_QUERY","OUTBOUND_CANCEL","OUTBOUND_RECEIPT","OUTBOUND_REPAIR","RETURN_CREATE","RETURN_QUERY","RETURN_RECEIPT","RETURN_CANCEL","RETURN_REVIEW","REFUND_RETURN_CREATE","REFUND_RETURN_QUERY","REFUND_RETURN_RECEIPT","REFUND_RETURN_CANCEL","REFUND_RETURN_REVIEW","REFUND_PARCEL_RECEIPT","REFUND_PARCEL_REVIEW","REFUND_PARCEL_LINK_REVIEW","CREATE","QUERY","CANCEL","RECEIPT");
        for(String key:Arrays.asList("connectionId","taskId")){String value=field(q,key,24);if(!value.isEmpty()){require(id(value)>0,"编号无效");add(f,"l."+(key.equals("taskId")?"task_id":"connection_id")+"=?",id(value));}}
        for(String key:Arrays.asList("warehouse","owner")){String value=field(q,key,255);if(!value.isEmpty())add(f,(key.equals("warehouse")?"COALESCE(ot.real_store_code,rt.real_store_code,fr.real_store_code,rp.real_store_code,rw.real_store_code,'')":"COALESCE(ot.owner_code,rt.owner_code,fr.owner_code,rp.owner_code,oi.owner_code,'')")+"=?",value);}
        return f;
    }
    private void documentTasks(Filter f,String company,String type,String number){
        // Resolve indexed scalar references first. Do not search large JSON snapshots or join all logs by order number.
        String column="EXCHANGE".equals(type)?"business_owner_sn":"ORDER".equals(type)?"order_sn":"PLATFORM".equals(type)?"platform_tid":"external_order";
        boolean purchase=Arrays.asList("ARRIVAL","PURCHASE").contains(type);
        List<Long> outbound=purchase||"REFUND".equals(type)?Collections.emptyList():db.jdbc.queryForList("SELECT id FROM wms_outbound_task WHERE company_code=? AND "+column+"=? ORDER BY id LIMIT 1001",Long.class,company,number);
        List<Long> inbound=purchase?db.jdbc.queryForList("SELECT it.id FROM wms_inbound_task it JOIN wms_tickets t ON t.id=it.ticket_id AND UPPER(t.company_code)=it.company_code WHERE it.company_code=? AND t."+("ARRIVAL".equals(type)?"relation_sn":"original_sn")+"=? ORDER BY it.id LIMIT 1001",Long.class,company,number):"WAREHOUSE".equals(type)?db.jdbc.queryForList("SELECT id FROM wms_inbound_task WHERE company_code=? AND external_order=? ORDER BY id LIMIT 1001",Long.class,company,number):Collections.emptyList();
        List<Long> returned=purchase||"REFUND".equals(type)?Collections.emptyList():db.jdbc.queryForList("SELECT id FROM wms_exchange_return WHERE company_code=? AND "+("EXCHANGE".equals(type)?"exchange_sn":column)+"=? ORDER BY id LIMIT 1001",Long.class,company,number);
        List<Long> refunded=purchase||"EXCHANGE".equals(type)?Collections.emptyList():db.jdbc.queryForList("SELECT id FROM wms_refund_return WHERE company_code=? AND "+("REFUND".equals(type)?"refund_sn":column)+"=? ORDER BY id LIMIT 1001",Long.class,company,number);
        require(outbound.size()+inbound.size()+returned.size()+refunded.size()<=1000,"关联任务超过1000张，请使用完整执行单号查询");List<String> branches=new ArrayList<>();
        if(!outbound.isEmpty()){branches.add("("+OUTBOUND+" AND l.task_id IN ("+String.join(",",Collections.nCopies(outbound.size(),"?"))+"))");f.args.addAll(outbound);}
        if(!inbound.isEmpty()){branches.add("("+INBOUND+" AND l.task_id IN ("+String.join(",",Collections.nCopies(inbound.size(),"?"))+"))");f.args.addAll(inbound);}
        if(!returned.isEmpty()){branches.add("("+RETURN+" AND l.task_id IN ("+String.join(",",Collections.nCopies(returned.size(),"?")) + "))");f.args.addAll(returned);}
        if(!refunded.isEmpty()){branches.add("("+REFUND+" AND l.task_id IN ("+String.join(",",Collections.nCopies(refunded.size(),"?"))+"))");f.args.addAll(refunded);}
        if(Arrays.asList("REFUND","ORDER","PLATFORM","WAREHOUSE").contains(type)){String col="REFUND".equals(type)?"m.refund_sn":"ORDER".equals(type)?"m.order_sn":"PLATFORM".equals(type)?"t.platform_tid":"t.external_order";List<String> parcels=db.jdbc.queryForList("SELECT DISTINCT p.parcel_sn FROM wms_refund_parcel_member m JOIN wms_refund_parcel p ON p.company_code=m.company_code AND p.id=m.parcel_id LEFT JOIN wms_refund_return t ON t.company_code=m.company_code AND t.id=m.task_id WHERE m.company_code=? AND "+col+"=? LIMIT 1001",String.class,company,number);require(parcels.size()<=1000,"关联包裹过多，请使用包裹号查询");if(!parcels.isEmpty()){branches.add("("+PARCEL+" AND l.ticket_sn IN ("+String.join(",",Collections.nCopies(parcels.size(),"?"))+"))");f.args.addAll(parcels);}}
        if(Arrays.asList("REFUND","ORDER","PLATFORM","WAREHOUSE").contains(type)){String columnName="REFUND".equals(type)?"m.refund_sn":"ORDER".equals(type)?"m.order_sn":"PLATFORM".equals(type)?"m.platform_tid":"d.external_order";List<String> shared=db.jdbc.queryForList("SELECT DISTINCT d.return_sn FROM wms_refund_parcel_member m JOIN wms_refund_parcel_document d ON d.company_code=m.company_code AND d.parcel_id=m.parcel_id WHERE m.company_code=? AND "+columnName+"=? LIMIT 1001",String.class,company,number);require(shared.size()<=1000,"关联共同THK过多，请缩小范围");if(!shared.isEmpty()){branches.add("("+REFUND+" AND l.ticket_sn IN ("+String.join(",",Collections.nCopies(shared.size(),"?"))+"))");f.args.addAll(shared);}}
        f.sql+=" AND "+(branches.isEmpty()?"1=0":"("+String.join(" OR ",branches)+")");
    }
    public Map<String,Object> list(String company,Map<String,String> q){
        Filter f=filter(company,q);long requestedSize=q.containsKey("size")?id(q.get("size")):50;require(requestedSize>=1&&requestedSize<=100,"每页最多100条");int size=(int)requestedSize;
        String after=field(q,"beforeId",24),before=field(q,"beforeTime",32);require(after.isEmpty()==before.isEmpty(),"分页游标不完整");
        if(!after.isEmpty()){require(id(after)>0,"分页游标无效");String ts=time(before).format(TIME);f.sql+=" AND (l.create_time<? OR (l.create_time=? AND l.id<?))";f.args.add(ts);f.args.add(ts);f.args.add(id(after));}
        List<Object> args=new ArrayList<>(f.args);args.add(size+1);List<Map<String,Object>> rows=db.jdbc.queryForList(SELECT+FROM+f.sql+" ORDER BY l.create_time DESC,l.id DESC LIMIT ?",args.toArray());boolean more=rows.size()>size;if(more)rows.remove(rows.size()-1);decorate(company,rows);
        Map<String,Object> next=null;if(more&&!rows.isEmpty()){Map<String,Object> last=rows.get(rows.size()-1);next=map("beforeId",last.get("id"),"beforeTime",timestamp(last.get("create_time")));}return map("rows",rows,"has_more",more,"next",next);
    }
    public Map<String,Object> summary(String company,Map<String,String> q){Filter f=filter(company,q);List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT l.result,COUNT(*) log_count"+FROM+f.sql+" GROUP BY l.result",f.args.toArray());return map("counts",rows,"total",rows.stream().mapToLong(r->id(r.get("log_count"))).sum());}
    public Map<String,Object> detail(String company,long id){List<Map<String,Object>> rows=db.jdbc.queryForList(SELECT+FROM+" WHERE l.company_code=? AND l.id=?",company,id);require(rows.size()==1,"日志不存在或不属于当前公司");decorate(company,rows);return rows.get(0);}
    public Map<String,Object> options(String company){return map("connections",db.jdbc.queryForList("SELECT id,name,provider,environment,enabled FROM wms_connection WHERE company_code=? ORDER BY id DESC",company),"warehouses",db.jdbc.queryForList("SELECT real_store_code code,wms_name name FROM wms_real_store_info WHERE UPPER(company_code)=? ORDER BY id",company),"owners",db.jdbc.queryForList("SELECT owner_code code,owner_name name FROM owner_info WHERE UPPER(company_code)=? ORDER BY id",company));}
    private void decorate(String company,List<Map<String,Object>> rows){
        Set<Long> out=new HashSet<>(),in=new HashSet<>(),refundTasks=new HashSet<>();for(Map<String,Object> row:rows)if(row.get("task_id")!=null){if(Arrays.asList("SALE_OUT","EXCHANGE_OUT").contains(row.get("business_type")))out.add(id(row.get("task_id")));else if("PURCHASE_IN".equals(row.get("business_type")))in.add(id(row.get("task_id")));else if("REFUND_IN".equals(row.get("business_type")))refundTasks.add(id(row.get("task_id")));}
        Map<Long,Map<String,Object>> events=group(company,out,"SELECT task_id,COUNT(*) events,SUM(post_state='POSTED') posted,SUM(post_state='FAILED') failed FROM wms_outbound_receipt_event WHERE company_code=? AND task_id IN (%s) GROUP BY task_id");
        Map<Long,Map<String,Object>> reviews=group(company,out,"SELECT task_id,COUNT(*) pending FROM wms_outbound_receipt_review WHERE company_code=? AND state='WAIT_REVIEW' AND task_id IN (%s) GROUP BY task_id");
        Map<Long,Map<String,Object>> inbound=group(company,in,"SELECT task_id,COUNT(*) events,SUM(posted=1) posted,SUM(posted=2) failed FROM wms_receipt_line WHERE company_code=? AND task_id IN (%s) GROUP BY task_id");
        Map<Long,Map<String,Object>> inboundReviews=group(company,in,"SELECT task_id,COUNT(*) pending FROM wms_inbound_receipt_review WHERE company_code=? AND state='WAIT_REVIEW' AND task_id IN (%s) GROUP BY task_id");
        Map<Long,Map<String,Object>> refundReviews=group(company,refundTasks,"SELECT task_id,COUNT(*) pending FROM wms_refund_return_review WHERE company_code=? AND state='WAIT_REVIEW' AND task_id IN (%s) GROUP BY task_id");
        Set<Long> parcelIds=new HashSet<>();for(Map<String,Object> row:rows)if(row.get("parcel_id")!=null)parcelIds.add(id(row.get("parcel_id")));
        Map<Long,Map<String,Object>> parcelPending=group(company,parcelIds,"SELECT parcel_id task_id,COUNT(*) pending FROM wms_refund_parcel_receipt WHERE company_code=? AND state='WAIT_ALLOCATION' AND parcel_id IN (%s) GROUP BY parcel_id");
        Map<Long,Map<String,Object>> parcelStock=group(company,parcelIds,"SELECT m.parcel_id task_id,SUM(t.accepted_quantity) accepted,SUM(t.posted_quantity) posted FROM wms_refund_parcel_member m JOIN wms_refund_return t ON t.company_code=m.company_code AND t.id=m.task_id WHERE m.company_code=? AND m.parcel_id IN (%s) GROUP BY m.parcel_id");
        for(Map<String,Object> row:rows){String stage="未关联业务单据",tone="info";long task=row.get("task_id")==null?0:id(row.get("task_id"));boolean sale=Arrays.asList("SALE_OUT","EXCHANGE_OUT").contains(row.get("business_type"));Map<String,Object> e=(sale?events:inbound).get(task);String state=text(row.get("dispatch_state"));
            if(!state.isEmpty()){stage="等待仓库受理";if(Arrays.asList("UNKNOWN","SENDING","CANCEL_PENDING").contains(state)){stage="仓库结果待确认";tone="warning";}else if("FAILED".equals(state)){stage="仓库下发失败";tone="danger";}else if("ACCEPTED".equals(state))stage="仓库已受理，等待"+(sale?"出库":"收货");else if("CANCELED".equals(state)){stage="仓库任务已取消";tone="success";}
                if(e!=null){long count=id(e.get("events")),posted=id(e.get("posted"));stage=id(e.get("failed"))>0?"回传记账异常":posted<count?"回传已接收，记账处理中":sale?"已接收出库回传均已记账":"已接收收货明细均已记账";tone=id(e.get("failed"))>0?"danger":posted<count?"warning":"success";if(!sale&&posted==count&&"COMPLETE".equals(row.get("receipt_state"))&&row.get("inventory_status")!=null&&id(row.get("inventory_status"))!=2){stage="收货明细已记账，单据汇总待完成";tone="warning";}}
                if(sale&&reviews.containsKey(task)){stage="出库回传待人工核对，冲突回传未扣账";tone="warning";}else if(sale&&Arrays.asList("PENDING","UNKNOWN").contains(text(row.get("cancel_state")))){stage="取消结果待确认，保留待截部分占用";tone="warning";}else if(sale&&"REJECTED".equals(row.get("cancel_state"))){stage="仓库拒绝取消，需核对";tone="warning";}
                if(!sale){String cancel=text(row.get("inbound_cancel_state")),cut=text(row.get("inbound_cutoff_state"));if(inboundReviews.containsKey(task)){stage="取消后收货待人工核对，冲突回传未入账";tone="warning";}else if("WAIT_REVIEW".equals(cut)){stage="到货截单待人工核对，保留原安排额度";tone="warning";}else if(Arrays.asList("PENDING","UNKNOWN").contains(cancel)){stage="取消结果待确认，保留到货安排额度";tone="warning";}else if("REJECTED".equals(cancel)){stage="仓库拒绝截单，原到货安排保留";tone="warning";}else if("CONFIRMED".equals(cancel)&&!"COMPLETED".equals(cut)){stage="仓库已截停，等待实收及记账核对";tone="warning";}else if("PROCESSING".equals(cut)){stage="到货截单处理中，保留待截部分安排";tone="warning";}else if("COMPLETED".equals(cut)&&(e==null||id(e.get("failed"))==0&&id(e.get("events"))==id(e.get("posted")))){stage="到货截单完成，已确认实收保留";tone="success";}}
            }if(Arrays.asList("SALE_RETURN","EXCHANGE_IN","REFUND_IN").contains(row.get("business_type"))){stage="COMPLETED".equals(row.get("receipt_state"))?"退回实收已记账，等待确认货物处理完成":"ACCEPTED".equals(state)?"退回单已受理，等待实收":"退回受理待核对";tone="COMPLETED".equals(row.get("receipt_state"))?"success":"info";if("CONFIRMED".equals(row.get("cancel_state"))){stage="本次安排剩余已截停，已实收入账保留";tone="success";}}if("REFUND_IN".equals(row.get("business_type"))&&refundReviews.containsKey(task)){stage="退回回传待人工核对，冲突回传未入账";tone="warning";}if(row.get("parcel_id")!=null){boolean pending=parcelPending.containsKey(id(row.get("parcel_id")));stage=pending?"合包回传待核对商品归属，未分配部分未入账":"合包回传已核对，具体实收与入账见关联THK";tone=pending?"warning":"info";}if(row.get("shared_document_id")!=null){Map<String,Object> stock=parcelStock.get(id(row.get("parcel_id")));if(parcelPending.containsKey(id(row.get("parcel_id")))){stage="共同THK实收待核对退款来源，未分配部分未入账";tone="warning";}else if("CONFIRMED".equals(row.get("cancel_state"))){stage="共同THK剩余已截停，全部来源的已实收入账保留";tone="success";}else if(stock!=null&&id(stock.get("accepted"))>id(stock.get("posted"))){stage="共同THK实收已分配，库存记账待完成";tone="warning";}else if(stock!=null&&id(stock.get("posted"))>0){stage="共同THK已确认实收均已入账；各退款独立结案";tone="success";}else{stage="ACCEPTED".equals(state)?"共同THK已受理，等待仓库实收":"共同THK受理结果待核对";tone="info";}}
            row.put("business_progress",stage);row.put("business_tone",tone);
        }
    }
    private Map<Long,Map<String,Object>> group(String company,Set<Long> ids,String sql){Map<Long,Map<String,Object>> rows=new HashMap<>();if(ids.isEmpty())return rows;List<Object> args=new ArrayList<>();args.add(company);args.addAll(ids);for(Map<String,Object> row:db.jdbc.queryForList(String.format(sql,String.join(",",Collections.nCopies(ids.size(),"?"))),args.toArray()))rows.put(id(row.get("task_id")),row);return rows;}
}
