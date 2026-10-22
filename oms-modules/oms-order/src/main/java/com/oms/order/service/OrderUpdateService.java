package com.oms.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Platform facts and adopted fulfillment snapshots are deliberately independent. */
public class OrderUpdateService {
    private final OrderRepository repository;
    private final OrderShipmentWmsClient warehouse;
    public OrderUpdateService(OrderRepository repository){this(repository,null);}
    public OrderUpdateService(OrderRepository repository,OrderShipmentWmsClient warehouse){this.repository=repository;this.warehouse=warehouse;}
    private TransactionTemplate tx(){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));}
    private static final List<String> RECIPIENT=Arrays.asList("receiver_name","receiver_mobile","receiver_phone","receiver_state","receiver_city","receiver_district","receiver_town","receiver_address","oaid");
    private static final Set<String> FORWARD=new HashSet<>(Arrays.asList("WAIT_BUYER_CONFIRM_GOODS","TRADE_FINISHED"));
    private static final Map<String,String> LABELS=new LinkedHashMap<>();
    static {LABELS.put("RECIPIENT","收货信息变更");LABELS.put("GOODS","商品变更待处理");LABELS.put("AMOUNT","金额变更待核对");LABELS.put("REFUND","售后影响履约");LABELS.put("CLOSE","平台交易关闭");LABELS.put("HOLD","平台暂缓发货");LABELS.put("STATUS","平台状态待核对");LABELS.put("TRADE","交易信息变更");LABELS.put("CONFLICT","来源版本冲突");LABELS.put("DATA","平台数据待核对");}
    private JsonNode trade(String payload){try{JsonNode t=JSON.readTree(payload).path("trade_fullinfo_get_response").path("trade");require(t.isObject(),"平台订单数据无法解析");return t;}catch(Exception e){throw new IllegalArgumentException("平台订单数据无法解析，请核对来源");}}
    private String value(JsonNode n,String key){return n.path(key).isNull()?"":n.path(key).isContainerNode()?n.path(key).toString():n.path(key).asText("");}
    private Map<String,JsonNode> lines(JsonNode t){Map<String,JsonNode> rows=new TreeMap<>();JsonNode n=t.path("orders").path("order");if(n.isArray())for(JsonNode l:n)rows.put(value(l,"oid"),l);else if(n.isObject())rows.put(value(n,"oid"),n);return rows;}
    private void compare(List<Map<String,Object>> result,JsonNode a,JsonNode b,String oid,String field,String category){String before=value(a,field),after=value(b,field);if((OrderDocumentInfo.TRADE_MONEY.contains(field)||OrderDocumentInfo.LINE_MONEY.contains(field)||"num".equals(field))&&!before.isEmpty()&&!after.isEmpty())try{if(new java.math.BigDecimal(before).compareTo(new java.math.BigDecimal(after))==0)return;}catch(NumberFormatException ignored){}if(!before.equals(after)){Map<String,Object> d=new LinkedHashMap<>();d.put("category",category);d.put("platform_oid",oid);d.put("field",field);d.put("before",before);d.put("after",after);result.add(d);}}
    List<Map<String,Object>> differences(String before,String after){
        JsonNode a=trade(before),b=trade(after);List<Map<String,Object>> result=new ArrayList<>();
        for(String f:RECIPIENT)compare(result,a,b,"",f,"RECIPIENT");
        for(String f:Arrays.asList("tid","seller_nick","type"))compare(result,a,b,"",f,"TRADE");
        for(String f:OrderDocumentInfo.TRADE_MONEY)compare(result,a,b,"",f,"AMOUNT");compare(result,a,b,"","status","STATUS");compare(result,a,b,"","top_hold","HOLD");
        for(String f:Arrays.asList("refund_status","refund_id"))compare(result,a,b,"",f,"REFUND");
        for(String f:OrderDocumentInfo.META)compare(result,a,b,"",f,"DISPLAY");
        Map<String,JsonNode> x=lines(a),y=lines(b);Set<String> ids=new TreeSet<>(x.keySet());ids.addAll(y.keySet());
        for(String oid:ids){JsonNode old=x.get(oid),next=y.get(oid);if(old==null||next==null){Map<String,Object> d=new LinkedHashMap<>();d.put("category","GOODS");d.put("platform_oid",oid);d.put("field","line");d.put("before",old==null?"不存在":old.toString());d.put("after",next==null?"不存在":next.toString());result.add(d);continue;}
            for(String f:Arrays.asList("num_iid","sku_id","outer_sku_id","outer_iid","num"))compare(result,old,next,oid,f,"GOODS");
            compare(result,old,next,oid,"sku_properties_name","GOODS");compare(result,old,next,oid,"title","DISPLAY");
            for(String f:OrderDocumentInfo.LINE_MONEY)compare(result,old,next,oid,f,"AMOUNT");
            for(String f:Arrays.asList("refund_status","refund_id"))compare(result,old,next,oid,f,"REFUND");compare(result,old,next,oid,"status","STATUS");
        }return result;
    }
    private boolean refundActive(JsonNode t){String s=value(t,"refund_status");if(!s.isEmpty()&&!Arrays.asList("NO_REFUND","CLOSED").contains(s))return true;for(JsonNode l:lines(t).values()){s=value(l,"refund_status");if(!s.isEmpty()&&!Arrays.asList("NO_REFUND","CLOSED").contains(s))return true;}return false;}
    private boolean refundFinal(JsonNode t){if("SUCCESS".equals(value(t,"refund_status")))return true;for(JsonNode l:lines(t).values())if("SUCCESS".equals(value(l,"refund_status")))return true;return false;}
    private String baseline(Map<String,Object> p){return p.get("processing_payload")==null?text(p,"raw_payload"):text(p,"processing_payload");}
    private Set<String> blockers(String company,Map<String,Object> p,String payload){
        Set<String> categories=new LinkedHashSet<>();try{
            JsonNode latest=trade(payload);new TmallOrderParser().parse(payload);
            Set<String> covered=new HashSet<>(repository.db().queryForList("SELECT difference_hash FROM oms_exchange_update_link WHERE company_code=? AND platform_order_id=? AND state='APPLIED'",String.class,company,p.get("id")));
            for(Map<String,Object> d:differences(baseline(p),payload))if(Arrays.asList("RECIPIENT","GOODS","AMOUNT","TRADE").contains(text(d,"category"))&&!covered.contains(hash(ExchangeService.canonical(d))))categories.add(text(d,"category"));
            if((refundActive(latest)||number(p,"refund_final_pending")==1)&&!RefundGuard.covers(repository,company,p,payload))categories.add("REFUND");
            if("1".equals(value(latest,"top_hold")))categories.add("HOLD");
            for(JsonNode line:lines(latest).values())if(value(line,"status").startsWith("TRADE_CLOSED"))categories.add("CLOSE");
            String status=value(latest,"status");if(status.startsWith("TRADE_CLOSED"))categories.add("CLOSE");
            else if(FORWARD.contains(status)){
                long shipped=0,required=0;if(p.get("business_order_id")!=null){long order=number(p,"business_order_id");shipped=repository.db().queryForObject("SELECT COALESCE(SUM(l.shipped_quantity),0) FROM oms_order_shipment_line l JOIN oms_order_shipment s ON s.company_code=l.company_code AND s.id=l.shipment_id WHERE s.company_code=? AND s.order_id=?",Long.class,company,order);required=repository.db().queryForObject("SELECT COALESCE(SUM(quantity-cancelled_quantity),0) FROM oms_order_line WHERE company_code=? AND order_id=?",Long.class,company,order);}
                if(required==0||shipped<required)categories.add("STATUS");
            }else if(!"WAIT_SELLER_SEND_GOODS".equals(status)&&p.get("business_order_id")!=null)categories.add("STATUS");
        }catch(IllegalArgumentException e){categories.add("DATA");}return categories;
    }
    void refreshProjection(String company,long id){Map<String,Object> p=lock(company,id);Set<String> remaining=blockers(company,p,text(p,"raw_payload"));if(text(p,"update_categories").contains("CONFLICT"))remaining.add("CONFLICT");repository.db().update("UPDATE oms_platform_order SET update_version=update_version+1,update_state=?,update_categories=?,update_summary=?,change_pending=? WHERE company_code=? AND id=?",remaining.isEmpty()?"COMPLETED":"WAITING",String.join(",",remaining),summary(remaining),remaining.isEmpty()?0:1,company,id);}
    private String summary(Set<String> categories){List<String> parts=new ArrayList<>();for(String c:categories)parts.add(LABELS.getOrDefault(c,c));return parts.isEmpty()?"平台信息已同步":String.join("；",parts)+"；整单暂停新增履约";}
    private boolean protectedOrder(Map<String,Object> p){return p.get("business_order_id")!=null||number(p,"demand_registered")!=0||repository.db().queryForObject("SELECT COUNT(*) FROM oms_platform_order_line WHERE company_code=? AND platform_order_id=? AND manual_sku_sn IS NOT NULL",Long.class,p.get("company_code"),p.get("id"))>0;}

    /** Called under the pull configuration and platform row locks, in the ingestion transaction. */
    Map<String,Object> ingest(String company,Map<String,Object> p,Map<String,Object> source){
        String payload=text(source,"jdp_response"),modified=text(source,"jdp_modified"),content=hash(payload);long id=number(p,"id");
        if(modified.compareTo(text(p,"source_modified"))<0)return result("SKIPPED","推送数据版本较旧，保留当前订单",id);
        boolean same=content.equals(hash(text(p,"raw_payload")));
        boolean refresh=modified.compareTo(text(p,"source_modified"))>0&&(text(p,"update_categories").contains("CONFLICT")||text(p,"update_categories").contains("STATUS"));
        if(same&&refresh){Set<String> check=blockers(company,p,payload);refresh=!String.join(",",check).equals(text(p,"update_categories"));}
        if(same&&!refresh){repository.db().update("UPDATE oms_platform_order SET source_modified=GREATEST(source_modified,?) WHERE company_code=? AND id=?",modified,company,id);return result("SKIPPED","订单内容未变化",id);}
        if(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_update WHERE company_code=? AND platform_order_id=? AND source_modified=? AND content_hash=?",Long.class,company,id,modified,content)>0)return result("SKIPPED","该来源版本已记录",id);
        boolean guarded=protectedOrder(p);List<Map<String,Object>> diff;String fingerprint,block;try{TmallOrderParser.Trade t=new TmallOrderParser().parse(payload);fingerprint=t.fingerprint;block=t.fulfillmentBlock;diff=differences(text(p,"raw_payload"),payload);}catch(IllegalArgumentException e){fingerprint=hash(payload);block="平台报文无法解析";diff=new ArrayList<>();Map<String,Object>d=new LinkedHashMap<>();d.put("category","DATA");d.put("field","payload");d.put("before","原版本");d.put("after","新报文无法解析");diff.add(d);}
        if(diff.isEmpty()&&!refresh){repository.db().update("UPDATE oms_platform_order SET raw_payload=?,source_modified=GREATEST(source_modified,?),platform_modified=? WHERE company_code=? AND id=?",payload,modified,source.get("modified"),company,id);OrderDocumentInfo.latest(repository,company,id,p.get("business_order_id")==null?null:number(p,"business_order_id"),payload);return result("SKIPPED","业务内容未变化",id);}
        boolean conflict=modified.equals(text(p,"source_modified"))&&!diff.isEmpty();long version=number(p,"update_version")+1;
        if(refundFinalSafe(payload)||(guarded&&refundFinalSafe(text(p,"raw_payload"))))p.put("refund_final_pending",1);
        Set<String> categories=guarded?blockers(company,p,payload):new LinkedHashSet<>();if(conflict)categories.add("CONFLICT");
        boolean pending=!categories.isEmpty();String state=conflict?"ERROR":pending?"WAITING":"COMPLETED";
        repository.db().update("INSERT INTO oms_order_update(company_code,platform_order_id,version,source_modified,content_hash,payload,differences,categories,state,blocking,reason,handled_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,IF(?=0,NOW(),NULL))",company,id,version,modified,content,payload,encode(diff),String.join(",",categories),state,pending?1:0,summary(categories),pending?1:0);
        if(!conflict)repository.db().update("UPDATE oms_order_update SET state='SUPERSEDED' WHERE company_code=? AND platform_order_id=? AND version<? AND state IN ('WAITING','PROCESSING','ERROR')",company,id,version);
        if(guarded&&p.get("processing_payload")==null){String adopted=text(p,"raw_payload");repository.db().update("UPDATE oms_platform_order SET processing_payload=?,processing_hash=? WHERE company_code=? AND id=?",adopted,text(p,"payload_hash"),company,id);p.put("processing_payload",adopted);}
        if(!conflict)repository.db().update("UPDATE oms_platform_order SET raw_payload=?,payload_hash=?,source_modified=?,platform_modified=?,platform_status=?,trade_type=?,fulfillment_block_reason=?,refund_final_pending=? WHERE company_code=? AND id=?",payload,fingerprint,modified,source.get("modified"),source.get("status"),source.get("type"),block,number(p,"refund_final_pending"),company,id);
        if(!conflict)OrderDocumentInfo.latest(repository,company,id,p.get("business_order_id")==null?null:number(p,"business_order_id"),payload);
        repository.db().update("UPDATE oms_platform_order SET update_version=?,update_state=?,update_categories=?,update_summary=?,change_pending=? WHERE company_code=? AND id=?",version,state,String.join(",",categories),summary(categories),pending?1:0,company,id);
        if(p.get("business_order_id")!=null)repository.db().update("UPDATE oms_order SET control_version=control_version+1 WHERE company_code=? AND id=?",company,p.get("business_order_id"));
        if(pending&&p.get("business_order_id")!=null)repository.db().update("UPDATE oms_order SET review_status='PENDING',review_decision='MANUAL_REQUIRED',review_reason='平台关键变更后需重新审核',review_by='',review_at=NULL,review_snapshot=NULL,review_evaluated_at=NULL WHERE company_code=? AND id=? AND status IN ('WAIT_ALLOCATION','CUTTING') AND (SELECT COALESCE(SUM(l.quantity-l.cancelled_quantity),0) FROM oms_order_line l WHERE l.company_code=oms_order.company_code AND l.order_id=oms_order.id)>(SELECT COALESCE(SUM(l.shipped_quantity),0) FROM oms_order_shipment_line l JOIN oms_order_shipment sh ON sh.company_code=l.company_code AND sh.id=l.shipment_id WHERE sh.company_code=oms_order.company_code AND sh.order_id=oms_order.id)",company,p.get("business_order_id"));
        if(!guarded&&!conflict)repository.db().update("UPDATE oms_platform_order SET processing_payload=NULL,processing_hash=NULL,conversion_status='PENDING',conversion_error='',exception_code='',exception_details=NULL,exception_line_count=0,system_failures=0,next_retry_at=NOW() WHERE company_code=? AND id=?",company,id);
        repository.log(company,id,"ORDER_UPDATE",state,summary(categories),"拉单任务");return result(pending?"CHANGE_PENDING":"UPDATED",summary(categories),id);
    }
    private boolean refundFinalSafe(String payload){try{return refundFinal(trade(payload));}catch(IllegalArgumentException e){return false;}}
    private Map<String,Object> result(String code,String message,long id){Map<String,Object> r=new LinkedHashMap<>();r.put("result",code);r.put("message",message);r.put("platform_order_id",id);return r;}

    private Map<String,Object> lock(String company,long platform){
        Map<String,Object> key=repository.raw(company,platform);repository.db().query("SELECT channel_id FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,key.get("channel_id"));
        return repository.db().query("SELECT * FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,platform).get(0);
    }
    private void version(Map<String,Object> p,Map<String,Object> input){require(input.containsKey("update_version")&&number(input,"update_version")==number(p,"update_version"),"平台订单已有新版本，请刷新后重新核对");require(!text(p,"update_categories").contains("CONFLICT"),"来源版本冲突，请先核对并取得可信的新版本");}
    private Map<String,Object> order(String company,Map<String,Object> p){require(p.get("business_order_id")!=null,"该平台订单尚未生成业务订单");return repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,p.get("business_order_id")).get(0);}
    private long activeShipments(String company,long order){return repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment s WHERE s.company_code=? AND s.order_id=? AND s.status<>'VOIDED' AND EXISTS(SELECT 1 FROM oms_order_shipment_line l WHERE l.company_code=s.company_code AND l.shipment_id=s.id AND l.quantity>l.shipped_quantity+l.cancelled_quantity)",Long.class,company,order);}
    public Map<String,Object> prepare(String company,long platform,Map<String,Object> input,String actor,OrderCutoffService cutoff){return tx().execute(s->{Map<String,Object> p=lock(company,platform);version(p,input);require(text(p,"update_categories").contains("RECIPIENT"),"没有待处理收货信息变更");List<Map<String,Object>> linked=repository.db().query("SELECT cutoff_id FROM oms_order_update WHERE company_code=? AND platform_order_id=? AND version=? AND cutoff_id IS NOT NULL",ROW,company,platform,p.get("update_version"));if(!linked.isEmpty())return report(company,platform);Map<String,Object> o=order(company,p);Map<String,Object> command=new LinkedHashMap<>();command.put("control_version",o.get("control_version"));command.put("mode","REPLAN");command.put("scope_all",true);command.put("reason","订单更新#"+number(p,"update_version")+"："+reason(input));cutoff.request(company,number(o,"id"),command,actor);long op=repository.db().queryForObject("SELECT cutoff_id FROM oms_order WHERE company_code=? AND id=?",Long.class,company,o.get("id"));repository.db().update("UPDATE oms_order_update SET cutoff_id=?,operator_name=? WHERE company_code=? AND platform_order_id=? AND version=?",op,actor,company,platform,p.get("update_version"));repository.db().update("UPDATE oms_platform_order SET update_state='PROCESSING' WHERE company_code=? AND id=?",company,platform);return report(company,platform);});}
    private String reason(Map<String,Object> input){String r=text(input,"reason").trim();require(!r.isEmpty()&&r.length()<=150,"请填写1至150字处理原因");return r;}
    public Map<String,Object> applyRecipient(String company,long platform,Map<String,Object> input,String actor){return tx().execute(s->{
        Map<String,Object> p=lock(company,platform);version(p,input);String reason=reason(input);require(text(p,"update_categories").contains("RECIPIENT"),"没有待处理收货信息变更");
        TmallOrderParser.Trade latest=new TmallOrderParser().parse(text(p,"raw_payload"));require(!latest.oaid.isEmpty()||(!latest.name.isEmpty()&&!latest.phone.isEmpty()&&!latest.address.isEmpty()),"最新收货信息不完整");
        if(p.get("business_order_id")!=null){Map<String,Object> o=order(company,p);require("WAIT_ALLOCATION".equals(text(o,"status")),"订单正在截单、已结束或不能修改，请先核对履约结果");require(!Arrays.asList("PROCESSING","UNCERTAIN").contains(text(o,"allocation_status")),"存在未确认分仓，请先核对原结果");
            repository.db().query("SELECT id FROM oms_order_shipment WHERE company_code=? AND order_id=? ORDER BY id FOR UPDATE",ROW,company,o.get("id"));require(activeShipments(company,number(o,"id"))==0,"仍有未撤回的发货单，请先撤回重发并核对库存回执");
            require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_outbound b JOIN oms_order_shipment s ON s.company_code=b.company_code AND s.id=b.shipment_id WHERE s.company_code=? AND s.order_id=? AND b.state IN ('PENDING','UNKNOWN','FAILED')",Long.class,company,o.get("id"))==0,"存在未确认出库记账，请先核对原结果");
            List<Long> dispatched=repository.db().queryForList("SELECT s.id FROM oms_order_shipment s JOIN oms_order_shipment_dispatch d ON d.company_code=s.company_code AND d.shipment_id=s.id WHERE s.company_code=? AND s.order_id=? AND d.attempts>0",Long.class,company,o.get("id"));
            if(!dispatched.isEmpty()){require(warehouse!=null,"仓库回传核对服务不可用");for(int from=0;from<dispatched.size();from+=100){List<Map<String,Object>> reviews=data(warehouse.reviewSummaries(company,dispatched.subList(from,Math.min(from+100,dispatched.size())),com.ruoyi.common.core.constant.SecurityConstants.INNER));require(reviews!=null,"仓库回传核对结果缺失");for(Map<String,Object> review:reviews)require(number(review,"pending_count")==0,"存在待人工核对的仓库回传，请先处理原出库事实");}}
            long shipped=repository.db().queryForObject("SELECT COALESCE(SUM(l.shipped_quantity),0) FROM oms_order_shipment_line l JOIN oms_order_shipment s ON s.company_code=l.company_code AND s.id=l.shipment_id WHERE s.company_code=? AND s.order_id=?",Long.class,company,o.get("id"));
            long total=repository.db().queryForObject("SELECT COALESCE(SUM(quantity-cancelled_quantity),0) FROM oms_order_line WHERE company_code=? AND order_id=?",Long.class,company,o.get("id"));require(shipped<total,"订单已全部发货或未发余量已终止，不能修改原发货信息");require(number(o,"allocation_quantity")<=shipped,"仍有未撤回分仓占用，请先核对分仓和截单结果");
            repository.db().update("UPDATE oms_order SET receiver_name=?,receiver_phone=?,receiver_address=?,oaid=?,review_status='PENDING',review_by='',review_at=NULL,review_reason='收货信息更新后待重新审核',review_decision='UNASSESSED',review_snapshot=NULL,review_evaluated_at=NULL,allocation_policy=NULL,control_version=control_version+1 WHERE company_code=? AND id=?",latest.name,latest.phone,latest.address,latest.oaid,company,o.get("id"));
        }
        try{ObjectNode adopted=(ObjectNode)JSON.readTree(baseline(p));ObjectNode target=(ObjectNode)adopted.path("trade_fullinfo_get_response").path("trade");JsonNode source=trade(text(p,"raw_payload"));for(String f:RECIPIENT){if(source.has(f))target.set(f,source.get(f));else target.remove(f);}String payload=adopted.toString();repository.db().update("UPDATE oms_platform_order SET processing_payload=?,processing_hash=?,receiver_name=?,receiver_phone=?,receiver_address=?,oaid=? WHERE company_code=? AND id=?",payload,new TmallOrderParser().parse(payload).fingerprint,latest.name,latest.phone,latest.address,latest.oaid,company,platform);p.put("processing_payload",payload);}catch(java.io.IOException e){throw new IllegalArgumentException("原业务快照无法解析");}
        Set<String> remaining=blockers(company,p,text(p,"raw_payload"));String state=remaining.isEmpty()?"COMPLETED":"WAITING";
        repository.db().update("UPDATE oms_platform_order SET update_version=update_version+1,update_state=?,update_categories=?,update_summary=?,change_pending=? WHERE company_code=? AND id=?",state,String.join(",",remaining),summary(remaining),remaining.isEmpty()?0:1,company,platform);
        repository.db().update("UPDATE oms_order_update SET state=?,categories=?,blocking=?,reason=?,operator_name=?,handled_at=NOW() WHERE company_code=? AND platform_order_id=? AND version=?",state,String.join(",",remaining),remaining.isEmpty()?0:1,"已应用收货信息；"+reason,actor,company,platform,p.get("update_version"));
        repository.log(company,platform,"ORDER_UPDATE_ADDRESS","SUCCESS","已采用平台收货信息，原发货单快照保留；"+reason+(remaining.isEmpty()?"；待重新审核":"；其他变更阻断保留"),actor);return report(company,platform);
    });}
    public Map<String,Object> event(String company,long platform,long event){
        repository.raw(company,platform);List<Map<String,Object>> rows=repository.db().query("SELECT id,differences FROM oms_order_update WHERE company_code=? AND platform_order_id=? AND id=?",ROW,company,platform,event);require(rows.size()==1,"更新记录不存在或无权访问");Map<String,Object> r=rows.get(0);try{r.put("differences",JSON.readValue(text(r,"differences"),List.class));}catch(Exception e){throw new IllegalArgumentException("更新差异记录无法读取");}return r;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> report(String company,long platform){
        Map<String,Object> p=repository.raw(company,platform),r=new LinkedHashMap<>();for(String key:Arrays.asList("id","platform_tid","business_order_id","update_version","update_state","update_categories","update_summary","change_pending","source_modified"))r.put(key,p.get(key));
        List<Map<String,Object>> current;try{current=differences(baseline(p),text(p,"raw_payload"));}catch(IllegalArgumentException e){current=Collections.emptyList();}for(Map<String,Object> d:current)d.put("difference_hash",hash(ExchangeService.canonical(d)));r.put("differences",current);
        r.put("exchanges",repository.db().query("SELECT id,exchange_sn,source_kind,dispute_id,state,stage,requested_quantity,platform_oid FROM oms_exchange WHERE company_code=? AND original_platform_order_id=? ORDER BY id DESC LIMIT 30",ROW,company,platform));
        List<Map<String,Object>> history=repository.db().query("SELECT id,version,source_modified,categories,state,blocking,reason,cutoff_id,operator_name,create_time,handled_at FROM oms_order_update WHERE company_code=? AND platform_order_id=? ORDER BY id DESC LIMIT 30",ROW,company,platform);r.put("history",history);
        r.put("can_apply_recipient",text(p,"update_categories").contains("RECIPIENT")&&!text(p,"update_categories").contains("CONFLICT"));
        if(p.get("business_order_id")!=null){Map<String,Object> o=repository.db().query("SELECT id,order_sn,status,cutoff_id,cutoff_state,allocation_status,allocation_quantity,manual_hold,review_status FROM oms_order WHERE company_code=? AND id=?",ROW,company,p.get("business_order_id")).get(0);r.put("order",o);r.put("active_shipments",activeShipments(company,number(o,"id")));r.put("cutoff_operations",repository.db().query("SELECT id,mode,state,last_error,completed_at FROM oms_order_cutoff WHERE company_code=? AND order_id=? ORDER BY id DESC LIMIT 5",ROW,company,o.get("id")));r.put("shipments",repository.db().query("SELECT s.id,s.shipment_sn,s.status,s.quantity,s.cancelled_quantity,(SELECT COALESCE(SUM(l.shipped_quantity),0) FROM oms_order_shipment_line l WHERE l.company_code=s.company_code AND l.shipment_id=s.id) shipped_quantity FROM oms_order_shipment s WHERE s.company_code=? AND s.order_id=? ORDER BY s.id",ROW,company,o.get("id")));}
        return r;
    }
}
