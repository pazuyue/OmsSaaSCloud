package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Durable, company-scoped delivery tasks. This phase never posts outbound stock or shipped quantities. */
@Service
public class WmsOutboundService {
    @Resource private WmsStore db;
    @Resource private WmsConnections connections;
    @Resource private WmsTenantRoutes tenantRoutes;
    @Resource private WmsInteractionLog logs;
    @Resource private List<WmsProtocol> protocols;
    private WmsProtocol protocol(Map<String,Object> c){WmsProtocol p=protocols.stream().filter(v->v.provider().equals(text(c.get("provider")))).findFirst().orElseThrow(()->new IllegalArgumentException("该仓库协议未接入"));require(p.outboundSupported(c),"该连接协议版本尚不支持发货单，请使用2.0协议配置");return p;}
    @SuppressWarnings("unchecked") public Map<String,Object> resolve(String company,Map<String,Object> payload){
        tenantRoutes.datasource(company);required(payload,"owner_code",64);required(payload,"real_store_code",255);Object raw=payload.get("store_codes");require(raw instanceof List&&!((List<?>)raw).isEmpty()&&((List<?>)raw).size()<=500,"发货单虚仓范围无效");Set<String> codes=new TreeSet<>();for(Object code:(List<?>)raw)require(code instanceof String&&((String)code).matches("[A-Za-z0-9_-]{1,64}")&&codes.add((String)code),"虚仓编码无效或重复");
        String marks=String.join(",",Collections.nCopies(codes.size(),"?"));List<Object> args=new ArrayList<>();args.add(company);args.addAll(codes);
        List<Map<String,Object>> stores=db.jdbc.queryForList("SELECT v.wms_simulation_code,v.status,v.outbound_mode,v.connection_id,v.external_warehouse,v.external_owner,o.owner_code,o.is_enable,r.status relation_status,w.real_store_code,w.status warehouse_status FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.id=v.owner_warehouse_id AND UPPER(r.company_code)=UPPER(v.company_code) JOIN owner_info o ON o.id=r.owner_id AND UPPER(o.company_code)=UPPER(r.company_code) JOIN wms_real_store_info w ON w.id=r.real_store_id AND UPPER(w.company_code)=UPPER(r.company_code) WHERE UPPER(v.company_code)=? AND v.wms_simulation_code IN ("+marks+") ORDER BY v.id",args.toArray());
        require(stores.size()==codes.size(),"发货单虚仓不存在或归属异常");Map<String,Object> first=stores.get(0);
        for(Map<String,Object> store:stores){require(id(store.get("status"))==2&&id(store.get("relation_status"))==2&&id(store.get("is_enable"))==2&&id(store.get("warehouse_status"))==2,"发货仓、货主或关联已停用");require(text(payload.get("owner_code")).equals(text(store.get("owner_code")))&&text(payload.get("real_store_code")).equals(text(store.get("real_store_code"))),"发货单与虚仓货主实体仓不一致");require(id(store.get("outbound_mode"))==1,"虚仓 "+text(store.get("wms_simulation_code"))+" 为自动虚拟出库，不能下发真实WMS；请在仓库资料维护出库方式");require(store.get("connection_id")!=null&&!text(store.get("external_warehouse")).isEmpty()&&!text(store.get("external_owner")).isEmpty(),"请维护虚仓仓库连接、外部仓库及货主编码");for(String field:Arrays.asList("connection_id","external_warehouse","external_owner"))require(text(first.get(field)).equals(text(store.get(field))),"同一发货单的虚仓下发目标不一致，请核对仓库配置");}
        Map<String,Object> connection=connections.usable(company,id(first.get("connection_id")));protocol(connection);
        return map("connection_id",connection.get("id"),"connection_name",connection.get("name"),"provider",connection.get("provider"),"api_version",connection.get("api_version"),"environment",connection.get("environment"),"external_warehouse",first.get("external_warehouse"),"external_owner",first.get("external_owner"));
    }
    private Object canonical(Object value){if(value instanceof Map){Map<String,Object> result=new TreeMap<>();((Map<?,?>)value).forEach((k,v)->result.put(k.toString(),canonical(v)));return result;}if(value instanceof List){List<Object> result=new ArrayList<>();for(Object item:(List<?>)value)result.add(canonical(item));return result;}return value;}
    @SuppressWarnings("unchecked") private void validate(Map<String,Object> payload){
        require(id(payload.get("shipment_id"))>0&&required(payload,"shipment_sn",64).matches("[A-Za-z0-9_-]{1,64}"),"发货单标识无效");required(payload,"platform_tid",64);required(payload,"warehouse_carrier_code",64);required(payload,"carrier_name",100);required(payload,"create_time",32);required(payload,"place_order_time",32);required(payload,"operate_time",32);required(payload,"shop_name",100);
        require(payload.get("receiver") instanceof Map,"收货信息缺失");Map<String,Object> receiver=(Map<String,Object>)payload.get("receiver");require(!text(receiver.get("oaid")).isEmpty()||(!text(receiver.get("name")).isEmpty()&&!text(receiver.get("mobile")).isEmpty()&&!text(receiver.get("detailAddress")).isEmpty()),"收货信息不完整且无平台OAID");
        Object raw=payload.get("lines");require(raw instanceof List&&!((List<?>)raw).isEmpty()&&((List<?>)raw).size()<=500,"发货单明细须为1至500行");long total=0;Set<Long> ids=new HashSet<>();Set<String> stores=new TreeSet<>();
        for(Object item:(List<?>)raw){require(item instanceof Map,"发货明细无效");Map<String,Object> line=(Map<String,Object>)item;long qty=id(line.get("quantity")),key=id(line.get("id"));require(key>0&&ids.add(key)&&qty>0&&qty<=100000000,"发货明细数量或行号无效");required(line,"sku_sn",128);required(line,"batch_code",100);required(line,"store_code",64);required(line,"platform_oid",64);stores.add(text(line.get("store_code")));total=Math.addExact(total,qty);}
        require(total==id(payload.get("quantity")),"发货明细与单据数量不一致");require(payload.get("store_codes") instanceof List&&new TreeSet<>((List<String>)payload.get("store_codes")).equals(stores),"明细与虚仓范围不一致");require(payload.get("route") instanceof Map,"下发目标快照缺失");
    }
    private Map<String,Object> prepare(String company,Map<String,Object> payload){return prepare(company,payload,false);}
    private Map<String,Object> prepare(String company,Map<String,Object> payload,boolean cancelOnly){
        validate(payload);String json=db.encode(canonical(payload));require(json.getBytes(StandardCharsets.UTF_8).length<=900000,"发货单下发快照超过限制");String hash=QimenProtocol.hash("SHA-256",json).toLowerCase(Locale.ROOT);
        return db.tx().execute(status->{Map<?,?> expected=(Map<?,?>)payload.get("route");db.one("SELECT id FROM wms_connection WHERE id=? AND company_code=? FOR UPDATE",expected.get("connection_id"),company);List<Map<String,Object>> old=db.jdbc.queryForList("SELECT * FROM wms_outbound_task WHERE company_code=? AND shipment_sn=? FOR UPDATE",company,payload.get("shipment_sn"));if(!old.isEmpty()){Map<String,Object> task=old.get(0);require(id(task.get("shipment_id"))==id(payload.get("shipment_id"))&&hash.equals(task.get("payload_hash")),"同一发货单下发内容发生变化，禁止重建仓库单据");return task;}
            Map<String,Object> target;if(cancelOnly){Map<String,Object> bound=connections.get(company,id(expected.get("connection_id")));for(String f:Arrays.asList("provider","api_version","environment"))require(text(bound.get(f)).equals(text(expected.get(f))),"原仓库连接身份不一致");target=new LinkedHashMap<>();expected.forEach((k,v)->target.put(k.toString(),v));}else target=resolve(company,payload);for(String field:Arrays.asList("connection_id","provider","api_version","environment","external_warehouse","external_owner"))require(text(target.get(field)).equals(text(expected.get(field))),"仓库下发目标已变化，请刷新核对");
            long key=db.insert("wms_outbound_task",map("company_code",company,"shipment_id",payload.get("shipment_id"),"shipment_sn",payload.get("shipment_sn"),"business_type",payload.getOrDefault("business_type","SALE"),"business_owner_sn",payload.getOrDefault("business_owner_sn",payload.getOrDefault("order_sn","")),"exchange_id",payload.get("exchange_id"),"order_id",payload.getOrDefault("order_id",0),"order_sn",payload.getOrDefault("order_sn",""),"platform_tid",payload.get("platform_tid"),"connection_id",target.get("connection_id"),"external_warehouse",target.get("external_warehouse"),"external_owner",target.get("external_owner"),"owner_code",payload.get("owner_code"),"real_store_code",payload.get("real_store_code"),"payload_json",json,"payload_hash",hash));return db.one("SELECT * FROM wms_outbound_task WHERE id=?",key);
        });
    }
    private Map<String,Object> task(String company,String sn){db.jdbc.update("UPDATE wms_outbound_task SET dispatch_state='UNKNOWN',last_error='下发进程中断，请查询仓库结果',lease_token=NULL,lease_until=NULL WHERE company_code=? AND shipment_sn=? AND dispatch_state='SENDING' AND lease_until<NOW()",company,sn);List<Map<String,Object>> rows=db.jdbc.queryForList("SELECT * FROM wms_outbound_task WHERE company_code=? AND shipment_sn=?",company,sn);return rows.isEmpty()?null:rows.get(0);}
    public Map<String,Object> status(String company,String sn){Map<String,Object> task=task(company,sn);return task==null?map("dispatch_state","MISSING","message","仓库对接侧暂无下发任务"):summary(task);}
    private Map<String,Object> summary(Map<String,Object> task){return map("id",task.get("id"),"shipment_id",task.get("shipment_id"),"shipment_sn",task.get("shipment_sn"),"payload_hash",task.get("payload_hash"),"cancel_state",task.get("cancel_state"),"dispatch_state",task.get("dispatch_state"),"external_order",task.get("external_order"),"warehouse_status",task.get("warehouse_status"),"last_error",task.get("last_error"),"query_message",task.get("query_message"),"last_query_time",task.get("last_query_time"),"attempts",task.get("attempts"),"message",!text(task.get("last_error")).isEmpty()?task.get("last_error"):"ACCEPTED".equals(task.get("dispatch_state"))?"仓库已确认接收，等待出库回传":"下发进度："+task.get("dispatch_state"));}
    public Map<String,Object> submit(String company,Map<String,Object> payload,boolean retry){
        Map<String,Object> prepared=prepare(company,payload);long key=id(prepared.get("id"));if(retry){db.tx().execute(s->{Map<String,Object> locked=db.one("SELECT * FROM wms_outbound_task WHERE id=? AND company_code=? FOR UPDATE",key,company);if("FAILED".equals(locked.get("dispatch_state"))){connections.usable(company,id(locked.get("connection_id")));db.jdbc.update("UPDATE wms_outbound_task SET dispatch_state='PENDING',last_error='' WHERE id=?",key);}return null;});}
        String token=UUID.randomUUID().toString();int claimed=db.jdbc.update("UPDATE wms_outbound_task SET dispatch_state='SENDING',attempts=attempts+1,lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 120 SECOND) WHERE company_code=? AND id=? AND dispatch_state='PENDING'",token,company,key);
        return claimed==1?send(company,db.one("SELECT * FROM wms_outbound_task WHERE id=? AND company_code=?",key,company),"CREATE",token):summary(task(company,text(prepared.get("shipment_sn"))));
    }
    public Map<String,Object> query(String company,String sn){Map<String,Object> task=task(company,sn);if(task==null)return status(company,sn);if(Arrays.asList("PENDING","SENDING").contains(task.get("dispatch_state")))return summary(task);String token=UUID.randomUUID().toString();int claimed=db.jdbc.update("UPDATE wms_outbound_task SET lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 120 SECOND) WHERE company_code=? AND id=? AND dispatch_state NOT IN ('PENDING','SENDING') AND (lease_until IS NULL OR lease_until<NOW())",token,company,task.get("id"));return claimed==1?send(company,task,"QUERY",token):summary(task);}
    public Map<String,Object> cancel(String company,String sn,String reason){return cancel(company,sn,reason,null);}
    public Map<String,Object> cancel(String company,String sn,String reason,Map<String,Object> payload){
        if(payload!=null){require(sn.equals(text(payload.get("shipment_sn"))),"取消快照与原单号不一致");prepare(company,payload,true);}
        require(!reason.trim().isEmpty()&&reason.length()<=200,"请填写截单原因");
        Map<String,Object> t=task(company,sn);require(t!=null,"仓库下发任务缺失，需核实原始受理结果");
        if("CONFIRMED".equals(text(t.get("cancel_state"))))return summary(t);
        if(Arrays.asList("PENDING","UNKNOWN").contains(text(t.get("cancel_state"))))return query(company,sn);
        if(Arrays.asList("UNKNOWN","SENDING").contains(text(t.get("dispatch_state"))))return query(company,sn);
        Map<String,Object> c=connections.get(company,id(t.get("connection_id")));
        String token=UUID.randomUUID().toString();
        Map<String,Object> claimed=db.tx().execute(status->{Map<String,Object> locked=db.one("SELECT * FROM wms_outbound_task WHERE company_code=? AND id=? FOR UPDATE",company,t.get("id"));
            require(locked.get("lease_until")==null||!((java.sql.Timestamp)locked.get("lease_until")).toLocalDateTime().isAfter(java.time.LocalDateTime.now()),"仓库交互正在执行，请稍后核对");
            require(db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_receipt_event WHERE company_code=? AND task_id=? AND post_state<>'POSTED'",Long.class,company,t.get("id"))==0&&db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_receipt_review WHERE company_code=? AND task_id=? AND state='WAIT_REVIEW'",Long.class,company,t.get("id"))==0,"存在待记账或待人工核对的出库回传，不能确认截单");
            if("PENDING".equals(text(locked.get("dispatch_state")))&&id(locked.get("attempts"))==0){db.jdbc.update("UPDATE wms_outbound_task SET dispatch_state='CANCELED',cancel_state='CONFIRMED',cancel_reason=? WHERE company_code=? AND id=?",reason,company,t.get("id"));return null;}
            require("QIMEN".equals(text(c.get("provider"))),"当前仓库协议尚未核实销售截单能力，请人工联系仓库并核对结果");
            require("ACCEPTED".equals(text(locked.get("dispatch_state"))),"仓库受理结果未明确，请先查询原下发结果");
            require(!Arrays.asList("DELIVERED","CLOSED").contains(text(locked.get("warehouse_status"))),"仓库已有出库状态，需先核对完整回传及余量取消能力");
            db.jdbc.update("UPDATE wms_outbound_task SET cancel_state='PENDING',cancel_reason=?,lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 120 SECOND) WHERE company_code=? AND id=?",reason,token,company,t.get("id"));locked.put("cancel_reason",reason);return locked;
        });
        return claimed==null?summary(task(company,sn)):send(company,claimed,"CANCEL",token);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> send(String company,Map<String,Object> task,String action,String token){
        long key=id(task.get("id")),start=System.currentTimeMillis(),log=0;boolean contacted=false,confirmed=false;String secret="",response="";
        try{
            Map<String,Object> c="CREATE".equals(action)?connections.usable(company,id(task.get("connection_id"))):connections.get(company,id(task.get("connection_id")));Map<String,Object> payload=db.json.readValue(text(task.get("payload_json")),Map.class);
            if("CREATE".equals(action)){Map<String,Object> now=resolve(company,payload);for(String field:Arrays.asList("connection_id","external_warehouse","external_owner"))require(text(now.get(field)).equals(text(task.get(field))),"原下发仓库映射已变化，请恢复原配置后重试");}
            secret=connections.secret(c);WmsProtocol p=protocol(c);WmsProtocol.Wire wire=p.outboundRequest(c,secret,action,task,payload);log=logs.start(company,id(c.get("id")),key,text(task.get("shipment_sn")),"OUT","OUTBOUND_"+action,wire.body);require(db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_task WHERE company_code=? AND id=? AND lease_token=? AND lease_until>DATE_ADD(NOW(),INTERVAL 30 SECOND)",Long.class,company,key,token)==1,"仓库交互租约已失效，本次未发送请求");contacted=true;response=WmsHttp.post(wire);WmsProtocol.Reply reply=p.outboundReply(action,response);
            require(reply.documentSn.isEmpty()||reply.documentSn.equals(task.get("shipment_sn")),"仓库响应发货单号不一致");require(reply.owner.isEmpty()||reply.owner.equals(task.get("external_owner")),"仓库响应货主不一致");require(reply.warehouse.isEmpty()||reply.warehouse.equals(task.get("external_warehouse")),"仓库响应仓库不一致");require(text(task.get("external_order")).isEmpty()||reply.externalOrder.isEmpty()||reply.externalOrder.equals(task.get("external_order")),"仓库响应外部单号变化，需核对");
            String state=text(task.get("dispatch_state")),message;
            if("CANCEL".equals(action)){
                confirmed=reply.success;state=reply.success?"CANCELED":state;message=reply.success?"仓库明确取消成功":"仓库拒绝取消："+reply.message;
                db.jdbc.update("UPDATE wms_outbound_task SET cancel_state=? WHERE company_code=? AND id=? AND lease_token=?",reply.success?"CONFIRMED":"REJECTED",company,key,token);
            }else if("CREATE".equals(action)){
                if(reply.success){require(!reply.externalOrder.isEmpty(),"仓库未返回外部单号，结果待确认");confirmed=true;state="ACCEPTED";message="仓库已确认接收，等待出库回传";}
                else{boolean duplicate=!reply.externalOrder.isEmpty()||reply.code.toUpperCase(Locale.ROOT).matches(".*(DUPLICATE|EXISTS).*")||reply.message.contains("已存在")||reply.message.contains("重复");state=duplicate?"UNKNOWN":"FAILED";message=(duplicate?"仓库可能已有单据，请先查询结果：":"仓库明确拒绝下发：")+reply.message;}
            }else{
                boolean known=Arrays.asList("NEW","ACCEPT","PARTDELIVERED","DELIVERED","EXCEPTION","CANCELED","CLOSED","REJECT","CANCELEDFAIL").contains(reply.status);
                if(reply.success&&known&&!Arrays.asList("NOT_FOUND","ORDER_NOT_FOUND").contains(reply.code)){require(reply.documentSn.equals(task.get("shipment_sn")),"查询结果缺少当前发货单标识");require(!reply.externalOrder.isEmpty(),"查询结果缺少外部仓库单号");confirmed=true;state="CANCELED".equals(reply.status)?"CANCELED":"REJECT".equals(reply.status)?"FAILED":"ACCEPTED";if("CANCELEDFAIL".equals(reply.status))db.jdbc.update("UPDATE wms_outbound_task SET cancel_state='REJECTED' WHERE company_code=? AND id=? AND lease_token=?",company,key,token);if("CANCELED".equals(reply.status))db.jdbc.update("UPDATE wms_outbound_task SET cancel_state='CONFIRMED' WHERE company_code=? AND id=? AND lease_token=?",company,key,token);message=Arrays.asList("PARTDELIVERED","DELIVERED","CLOSED").contains(reply.status)?"仓库状态："+reply.status+"；等待完整出库明细回传，实际发货数量尚未更新":"仓库状态："+reply.status;}
                else message=Arrays.asList("NOT_FOUND","ORDER_NOT_FOUND").contains(reply.code)||"NOT_FOUND".equals(reply.status)?"仓库查无单据，请核对受理记录；本次不会重新下发":"仓库查询未确认当前单据状态："+(reply.message.isEmpty()?reply.status:reply.message);
            }
            message=cut(message);String error=Arrays.asList("ACCEPTED","CANCELED").contains(state)?"":message;String recordedStatus=confirmed?reply.status:"";
            db.jdbc.update("UPDATE wms_outbound_task SET dispatch_state=?,external_order=IF(?='',external_order,?),warehouse_status=IF(?='',warehouse_status,?),last_error=?,query_message=IF(?='QUERY',?,query_message),last_query_time=IF(?='QUERY',NOW(),last_query_time) WHERE company_code=? AND id=? AND lease_token=?",state,reply.externalOrder,reply.externalOrder,recordedStatus,recordedStatus,error,action,message,action,company,key,token);
            logs.finish(log,confirmed?"SUCCESS":"FAILED",response.replace(secret,"***"),message.replace(secret,"***"),System.currentTimeMillis()-start);
        }catch(Exception e){String error=e instanceof java.net.SocketTimeoutException?"仓库请求超时，结果待确认":e instanceof IllegalArgumentException?text(e.getMessage()):"仓库交互未确认："+e.getClass().getSimpleName();error=cut(secret.isEmpty()?error:error.replace(secret,"***"));
            if("CANCEL".equals(action))db.jdbc.update("UPDATE wms_outbound_task SET cancel_state=?,last_error=? WHERE company_code=? AND id=? AND lease_token=?",contacted?"UNKNOWN":"REJECTED",error,company,key,token);
            else if("CREATE".equals(action))db.jdbc.update("UPDATE wms_outbound_task SET dispatch_state=?,last_error=? WHERE company_code=? AND id=? AND lease_token=?",contacted?"UNKNOWN":"FAILED",error,company,key,token);else db.jdbc.update("UPDATE wms_outbound_task SET query_message=?,last_query_time=NOW() WHERE company_code=? AND id=? AND lease_token=?",error,company,key,token);
            if(log==0)log=logs.start(company,id(task.get("connection_id")),key,text(task.get("shipment_sn")),"OUT","OUTBOUND_"+action,"");logs.finish(log,"UNKNOWN",secret.isEmpty()?response:response.replace(secret,"***"),error,System.currentTimeMillis()-start);
        }finally{db.jdbc.update("UPDATE wms_outbound_task SET lease_until=NULL,lease_token=NULL WHERE company_code=? AND id=? AND lease_token=?",company,key,token);}
        Map<String,Object> result=summary(task(company,text(task.get("shipment_sn"))));if("QUERY".equals(action))result.put("query_success",confirmed&&"ACCEPTED".equals(result.get("dispatch_state")));return result;
    }
    private String cut(String value){return value.substring(0,Math.min(1000,value.length()));}
}
