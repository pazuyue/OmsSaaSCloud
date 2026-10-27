package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Exact physical document reads only: no reconciliation, warehouse commands or inventory writes. */
@Service public class ReturnDocumentRead {
    @Resource WmsStore db;
    @Resource WmsTenantRoutes routes;
    private static String marks(Collection<?> ids){return String.join(",",Collections.nCopies(ids.size(),"?"));}
    private List<Object> args(String c,Collection<?> ids){List<Object> out=new ArrayList<>();out.add(c);out.addAll(ids);return out;}
    private String table(String kind){return "REFUND".equals(kind)?"wms_refund_return":"wms_exchange_return";}
    private List<Map<String,Object>> sources(String c,String sn){
        List<Map<String,Object>> out=new ArrayList<>();
        for(String kind:Arrays.asList("REFUND","EXCHANGE")){
            String t=table(kind),key="REFUND".equals(kind)?"plan_id":"exchange_id",owner="REFUND".equals(kind)?"refund_sn":"exchange_sn";
            String lookup="SELECT id FROM "+t+" WHERE company_code=? AND return_sn=?";
            lookup+=" UNION SELECT m.task_id FROM wms_shared_return_document d JOIN wms_shared_return_member m ON m.company_code=d.company_code AND m.parcel_id=d.parcel_id WHERE d.company_code=? AND d.return_sn=? AND m.owner_kind='"+kind+"'";
            List<Object> a=new ArrayList<>(Arrays.asList(c,sn,c,sn));
            if("REFUND".equals(kind)){lookup+=" UNION SELECT m.task_id FROM wms_refund_parcel_document d JOIN wms_refund_parcel_member m ON m.company_code=d.company_code AND m.parcel_id=d.parcel_id AND m.generation=d.generation WHERE d.company_code=? AND d.return_sn=?";a.add(c);a.add(sn);}a.add(c);
            List<Map<String,Object>> part=db.jdbc.queryForList("SELECT t.id task_id,'"+kind+"' owner_kind,t."+key+" source_id,t."+owner+" owner_sn,t.sku_sn,t.store_code,t.real_store_code,t.owner_code,t.quantity,t.accepted_quantity,t.posted_quantity,t.cancelled_quantity,t.cancel_state,t.receipt_state,t.unit_cost,t.generation,t.original_shipment_sn,t.last_error,t.create_time FROM ("+lookup+") selected STRAIGHT_JOIN "+t+" t ON t.id=selected.id WHERE t.company_code=? ORDER BY t.id LIMIT 501",a.toArray());
            require(part.size()<=500,"仓单来源超过500项，请核对重复安排");out.addAll(part);
        }
        require(!out.isEmpty(),"实际退货入库单不存在或不属于当前公司");return out;
    }
    private Set<Long> ids(List<Map<String,Object>> sources,String kind){Set<Long> out=new LinkedHashSet<>();for(Map<String,Object> s:sources)if(kind.equals(s.get("owner_kind")))out.add(id(s.get("task_id")));return out;}
    @SuppressWarnings("unchecked") private Map<String,Object> object(Object raw){try{return raw instanceof Map?(Map<String,Object>)raw:db.json.readValue(text(raw),Map.class);}catch(Exception invalid){return Collections.emptyMap();}}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> list(Object raw){return raw instanceof List?(List<Map<String,Object>>)raw:Collections.emptyList();}
    private void receipt(Map<String,Object> row){
        Map<String,Object> payload=object(row.remove("payload"));row.put("final_confirmation",Boolean.TRUE.equals(payload.get("complete")));List<Map<String,Object>> lines=new ArrayList<>();
        for(Map<String,Object> l:list(payload.get("lines")))lines.add(map("sku_sn",l.get("sku"),"batch_code",l.get("batch"),"good_qty",l.get("good"),"bad_qty",l.get("bad"),"source_line",l.get("lineNo")));
        row.put("lines",lines);Map<String,Object> allocation=object(row.remove("allocation_json"));List<Map<String,Object>> assignments=new ArrayList<>();for(Map<String,Object> a:list(allocation.get("allocations")))assignments.add(map("line_index",a.get("line_index"),"member_id",a.get("member_id"),"good_qty",a.get("good_qty"),"bad_qty",a.get("bad_qty")));row.put("allocations",assignments);row.remove("payload_hash");ReturnQueryWarehouse.formatTimes(row);
    }
    public Map<String,Object> read(String c,String sn,String section,int page,int size){
        routes.datasource(c);require(sn!=null&&sn.length()<=64&&!sn.startsWith("RSL"),"请提供实际退货入库单号");require(Arrays.asList("SUMMARY","LINES","RECEIPTS","REVIEWS").contains(section),"退货明细类型无效");require(page>=1&&page<=10000&&size>=1&&size<=50,"每页最多50条");
        List<Map<String,Object>> sources=sources(c,sn);
        if("SUMMARY".equals(section)){
            for(String kind:Arrays.asList("REFUND","EXCHANGE")){Set<Long> ids=ids(sources,kind);if(ids.isEmpty())continue;Map<Long,Map<String,Object>> grades=new HashMap<>();
                for(Map<String,Object> g:db.jdbc.queryForList("SELECT l.task_id,SUM(l.good_qty) good_qty,SUM(l.bad_qty) bad_qty,SUM(IF(l.posted=1,l.good_qty,0)) posted_good,SUM(IF(l.posted=1,l.bad_qty,0)) posted_bad,MAX(e.final_confirmation) final_confirmation FROM "+table(kind)+"_line l JOIN "+table(kind)+"_event e ON e.company_code=l.company_code AND e.id=l.event_id WHERE l.company_code=? AND l.task_id IN ("+marks(ids)+") GROUP BY l.task_id",args(c,ids).toArray()))grades.put(id(g.get("task_id")),g);
                for(Map<String,Object> s:sources)if(kind.equals(s.get("owner_kind"))){Map<String,Object> g=grades.get(id(s.get("task_id")));s.putAll(g==null?map("good_qty",0,"bad_qty",0,"posted_good",0,"posted_bad",0,"final_confirmation",0):g);ReturnQueryWarehouse.formatTimes(s);}
            }
            return map("sources",sources,"return_sn",sn);
        }
        List<String> sql=new ArrayList<>();List<Object> allArgs=new ArrayList<>();
        // Shared receipts are the original physical events. Do not repeat their derived private events.
        if("RECEIPTS".equals(section)){
            List<Map<String,Object>> shared=db.jdbc.queryForList("SELECT id,parcel_id,'SHARED' receipt_kind FROM wms_shared_return_document WHERE company_code=? AND return_sn=? UNION ALL SELECT id,parcel_id,'REFUND' receipt_kind FROM wms_refund_parcel_document WHERE company_code=? AND return_sn=?",c,sn,c,sn);
            if(!shared.isEmpty()){
                Map<String,Object> d=shared.get(0);String t="SHARED".equals(d.get("receipt_kind"))?"wms_shared_return_receipt":"wms_refund_parcel_receipt";
                String select="SELECT id,message_id,quantity,state,source_log_id,reason,actor,payload,allocation_json,create_time"+("SHARED".equals(d.get("receipt_kind"))?",allocation_method":",'' allocation_method")+" FROM "+t+" WHERE company_code=? AND document_id=?";
                List<Map<String,Object>> rows=db.jdbc.queryForList(select+" ORDER BY id DESC LIMIT ? OFFSET ?",c,d.get("id"),size,(page-1)*size);for(Map<String,Object> r:rows)receipt(r);
                boolean common="SHARED".equals(d.get("receipt_kind"));Map<Long,Map<String,Object>> owners=new HashMap<>();
                for(Map<String,Object> m:db.jdbc.queryForList(common?"SELECT id,owner_kind,owner_sn,sku_sn FROM wms_shared_return_member WHERE company_code=? AND parcel_id=?":"SELECT id,'REFUND' owner_kind,refund_sn owner_sn,sku_sn FROM wms_refund_parcel_member WHERE company_code=? AND parcel_id=?",c,d.get("parcel_id")))owners.put(id(m.get("id")),m);
                for(Map<String,Object> r:rows)for(Map<String,Object> assignment:list(r.get("allocations"))){Object mid=assignment.get("member_id");Map<String,Object> owner=mid==null?null:owners.get(id(mid));if(owner!=null)for(String key:Arrays.asList("owner_kind","owner_sn","sku_sn"))assignment.put(key,owner.get(key));}
                return map("rows",rows,"total",db.jdbc.queryForObject("SELECT COUNT(*) FROM "+t+" WHERE company_code=? AND document_id=?",Long.class,c,d.get("id")),"receipt_kind",d.get("receipt_kind"));
            }
        }
        for(String kind:Arrays.asList("REFUND","EXCHANGE")){Set<Long> ids=ids(sources,kind);if(ids.isEmpty())continue;String t=table(kind),owner="REFUND".equals(kind)?"refund_sn":"exchange_sn";String fields;
            if("LINES".equals(section))fields="l.id,l.task_id,'"+kind+"' owner_kind,t."+owner+" owner_sn,l.sku_sn,l.batch_code,l.good_qty,l.bad_qty,l.posted,t.unit_cost,t.store_code,t.original_shipment_sn,e.message_id,e.source_log_id,e.final_confirmation,e.state,COALESCE(NULLIF(e.last_error,''),t.last_error) last_error,e.create_time,CONCAT('"+("REFUND".equals(kind)?"REFUND_RETURN_":"EXCHANGE_RETURN_")+"',l.id) inventory_relation FROM "+t+"_line l JOIN "+t+"_event e ON e.company_code=l.company_code AND e.id=l.event_id JOIN "+t+" t ON t.company_code=l.company_code AND t.id=l.task_id WHERE l.company_code=? AND l.task_id IN ("+marks(ids)+")";
            else if("RECEIPTS".equals(section))fields="e.id,e.task_id,'"+kind+"' owner_kind,t."+owner+" owner_sn,e.message_id,e.quantity,e.state,e.source_log_id,e.last_error reason,'' actor,e.payload,NULL allocation_json,e.create_time,'' allocation_method FROM "+t+"_event e JOIN "+t+" t ON t.company_code=e.company_code AND t.id=e.task_id WHERE e.company_code=? AND e.task_id IN ("+marks(ids)+")";
            else fields="r.id,r.task_id,'"+kind+"' owner_kind,t."+owner+" owner_sn,r.message_id,r.quantity,r.state,r.reason,r.actor,r.source_log_id,r.create_time FROM "+t+"_review r JOIN "+t+" t ON t.company_code=r.company_code AND t.id=r.task_id WHERE r.company_code=? AND r.task_id IN ("+marks(ids)+")";
            sql.add("SELECT "+fields);allArgs.addAll(args(c,ids));
        }
        String union=String.join(" UNION ALL ",sql);long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM ("+union+") counted",Long.class,allArgs.toArray());allArgs.add(size);allArgs.add((page-1)*size);
        List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT * FROM ("+union+") records ORDER BY create_time DESC,id DESC,owner_kind LIMIT ? OFFSET ?",allArgs.toArray());for(Map<String,Object> r:rows){if("RECEIPTS".equals(section))receipt(r);else ReturnQueryWarehouse.formatTimes(r);}
        return map("rows",rows,"total",total,"receipt_kind","INDEPENDENT");
    }
}
