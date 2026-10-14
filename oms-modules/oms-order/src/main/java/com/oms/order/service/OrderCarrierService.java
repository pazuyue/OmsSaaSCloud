package com.oms.order.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Carrier selection changes document routing only, never inventory or shipping quantities. */
@Service
public class OrderCarrierService {
    @Resource private OrderRepository repository;
    @Resource private OrderCatalog catalog;
    public OrderCarrierService(){}
    OrderCarrierService(OrderRepository repository){this.repository=repository;}
    private TransactionTemplate tx(){TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));tx.setTimeout(30);return tx;}
    @SuppressWarnings("unchecked") private Map<String,Object> policy(Object value){try{return JSON.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new IllegalArgumentException("快递规则已损坏，请重新配置");}}
    @SuppressWarnings("unchecked") private void decodeCarrier(Map<String,Object> row){try{row.put("services",JSON.readValue(text(row,"services_json"),List.class));row.remove("services_json");}catch(Exception e){throw new IllegalArgumentException("快递服务范围已损坏，请重新配置");}}
    private List<Map<String,Object>> carriers(String company,boolean lock){List<Map<String,Object>> rows=repository.db().query("SELECT * FROM oms_order_carrier WHERE company_code=? ORDER BY id"+(lock?" FOR UPDATE":""),ROW,company);for(Map<String,Object> row:rows)decodeCarrier(row);return rows;}
    private Set<String> warehouses(List<Map<String,Object>> rows){Set<String> result=new HashSet<>();for(Map<String,Object> row:rows)result.add(OrderCarrierPolicy.warehouseKey(row));return result;}
    public Map<String,Object> catalog(String company){Map<String,Object> result=new LinkedHashMap<>();result.put("carriers",carriers(company,false));result.put("warehouses",catalog.warehouses(company));result.put("provinces",OrderCarrierPolicy.PROVINCES);return result;}

    @SuppressWarnings("unchecked") public void saveCarrier(String company,Map<String,Object> input,String actor){
        String code=text(input,"carrier_code"),name=text(input,"carrier_name").trim();require(code.matches("[A-Za-z0-9_-]{1,64}")&&!name.isEmpty()&&name.length()<=100,"请填写有效快递编码和名称");require(input.containsKey("enabled")&&Arrays.asList(0L,1L).contains(number(input,"enabled")),"快递启用状态无效");
        Set<String> active=warehouses(catalog.warehouses(company));Object raw=input.get("services");require(raw instanceof List&&((List<?>)raw).size()<=100,"仓库快递服务范围无效或超过100项");List<Map<String,Object>> services=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object item:(List<?>)raw){require(item instanceof Map,"仓库服务范围无效");Map<String,Object> row=(Map<String,Object>)item;String owner=text(row,"owner_code"),store=text(row,"real_store_code"),warehouseCode=text(row,"warehouse_carrier_code");require(!owner.isEmpty()&&owner.length()<=64&&!store.isEmpty()&&store.length()<=255&&warehouseCode.matches("[A-Za-z0-9_-]{1,64}"),"请填写货主实体仓及仓库快递编码");require(number(input,"enabled")==0||active.contains(OrderCarrierPolicy.warehouseKey(row)),"仓库已停用或不属于当前公司");require(seen.add(OrderCarrierPolicy.warehouseKey(row)),"货主实体仓服务范围不能重复");List<String> allowed=OrderCarrierPolicy.provinces(row.get("provinces")),excluded=OrderCarrierPolicy.provinces(row.get("excluded_provinces"));require(Collections.disjoint(allowed,excluded),"同一省份不能同时允许和排除");
            Map<String,Object> normalized=new LinkedHashMap<>();normalized.put("owner_code",owner);normalized.put("real_store_code",store);normalized.put("warehouse_carrier_code",warehouseCode);normalized.put("provinces",allowed);normalized.put("excluded_provinces",excluded);services.add(normalized);
        }
        require(number(input,"enabled")==0||!services.isEmpty(),"启用快递须至少配置一个仓库服务范围");
        require(encode(services).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=48000,"仓库配送范围过大，请减少配置项");
        tx().execute(status->{List<Map<String,Object>> found=repository.db().query("SELECT revision FROM oms_order_carrier WHERE company_code=? AND carrier_code=? FOR UPDATE",ROW,company,code);require(input.containsKey("revision")&&(found.isEmpty()?0:number(found.get(0),"revision"))==number(input,"revision"),"快递资料已变化，请刷新后重试");
            repository.db().update("INSERT INTO oms_order_carrier(company_code,carrier_code,carrier_name,enabled,services_json,updated_by) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE carrier_name=VALUES(carrier_name),enabled=VALUES(enabled),services_json=VALUES(services_json),updated_by=VALUES(updated_by),revision=revision+1",company,code,name,number(input,"enabled"),encode(services),actor);return null;});
    }
    public List<Map<String,Object>> configurations(String company){
        List<Map<String,Object>> rows=repository.db().query("SELECT c.channel_id,c.channel_name,p.policy_json,p.revision FROM oms_order_pull_config c LEFT JOIN oms_order_carrier_config p ON p.company_code=c.company_code AND p.channel_id=c.channel_id WHERE c.company_code=? ORDER BY c.channel_id",ROW,company);
        for(Map<String,Object> row:rows){Object json=row.remove("policy_json");row.put("configured",json!=null);row.put("policy",json==null?OrderCarrierPolicy.defaults():policy(json));if(row.get("revision")==null)row.put("revision",0);}return rows;
    }
    @SuppressWarnings("unchecked") public void saveConfiguration(String company,Map<String,Object> input,String actor){
        long channel=number(input,"channel_id");catalog.validateShop(company,channel);require(input.get("policy") instanceof Map,"快递规则无效");Set<String> active=warehouses(catalog.warehouses(company));
        tx().execute(status->{require(repository.db().query("SELECT id FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel).size()==1,"请先接入店铺");List<Map<String,Object>> old=repository.db().query("SELECT revision FROM oms_order_carrier_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel);require(input.containsKey("revision")&&(old.isEmpty()?0:number(old.get(0),"revision"))==number(input,"revision"),"快递规则已变化，请刷新后重试");Set<String> enabled=new HashSet<>();for(Map<String,Object> carrier:carriers(company,true))if(number(carrier,"enabled")==1)enabled.add(text(carrier,"carrier_code"));Map<String,Object> normalized=OrderCarrierPolicy.validate((Map<String,Object>)input.get("policy"),enabled,active);
            require(encode(normalized).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=48000,"店铺快递规则过大，请减少配置项");repository.db().update("INSERT INTO oms_order_carrier_config(company_code,channel_id,policy_json,updated_by) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE policy_json=VALUES(policy_json),updated_by=VALUES(updated_by),revision=revision+1",company,channel,encode(normalized),actor);return null;});
    }

    private Map<String,Object> lock(String company,long id,long version){
        List<Map<String,Object>> keys=repository.db().query("SELECT s.order_id,s.channel_id,o.platform_order_id FROM oms_order_shipment s JOIN oms_order o ON o.company_code=s.company_code AND o.id=s.order_id WHERE s.company_code=? AND s.id=?",ROW,company,id);require(keys.size()==1,"发货单不存在或无权访问");Map<String,Object> key=keys.get(0);
        List<Map<String,Object>> shop=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,key.get("channel_id"));require(shop.size()==1,"店铺配置不存在");require(shop.get(0).get("lease_until")==null||time(text(shop.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺正在处理，请稍后重试");
        List<Map<String,Object>> platforms=repository.db().query("SELECT * FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,key.get("platform_order_id"));require(platforms.size()==1,"来源平台订单不存在");Map<String,Object> platform=platforms.get(0);
        Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,key.get("order_id")).get(0);
        require("WAIT_ALLOCATION".equals(text(order,"status"))&&"APPROVED".equals(text(order,"review_status")),"来源订单已停止履约或尚未审核");require(number(order,"manual_hold")==0,"来源订单已人工挂起");String block=OrderReviewPolicy.systemBlock(platform);require(block.isEmpty(),block);require(number(platform,"business_order_id")==number(key,"order_id")&&"SUCCESS".equals(text(platform,"conversion_status")),"来源订单关联不一致");
        Map<String,Object> shipment=repository.db().query("SELECT * FROM oms_order_shipment WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);require(number(shipment,"control_version")==version,"发货单已变化，请刷新后重试");require(Arrays.asList("WAIT_CARRIER","WAIT_DISPATCH").contains(text(shipment,"status")),"发货单已下发、处理或作废，不能调整快递");require(repository.db().queryForObject("SELECT COALESCE(SUM(shipped_quantity),0) FROM oms_order_shipment_line WHERE company_code=? AND shipment_id=?",Long.class,company,id)==0,"发货单已有出库数量，不能调整快递");require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_dispatch WHERE company_code=? AND shipment_id=?",Long.class,company,id)==0,"发货单已提交仓库，下发快照已固定，不能调整快递");require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_virtual_outbound WHERE company_code=? AND shipment_id=?",Long.class,company,id)==0,"发货单已提交虚拟出库，不能调整快递");shipment.put("platform_order_id",key.get("platform_order_id"));
        if(text(shipment,"receiver_province").isEmpty())shipment.put("receiver_province",receiverProvince(platform));return shipment;
    }
    public static String receiverProvince(Map<String,Object> platform){try{return OrderCarrierPolicy.province(JSON.readTree(text(platform,"processing_payload")).path("trade_fullinfo_get_response").path("trade").path("receiver_state").asText(""));}catch(Exception e){return "";}}
    private Map<String,Object> config(String company,long channel,boolean lock){List<Map<String,Object>> rows=repository.db().query("SELECT * FROM oms_order_carrier_config WHERE company_code=? AND channel_id=?"+(lock?" FOR UPDATE":""),ROW,company,channel);return rows.isEmpty()?null:rows.get(0);}
    private void log(String company,Map<String,Object> shipment,String action,String result,String message,String actor){repository.db().update("INSERT INTO oms_order_shipment_log(company_code,shipment_id,action,result,message,operator_name) VALUES (?,?,?,?,?,?)",company,shipment.get("id"),action,result,message,actor);repository.log(company,number(shipment,"platform_order_id"),action,result,text(shipment,"shipment_sn")+"："+message,actor);repository.shipmentChanged(company,number(shipment,"id"));}
    private Map<String,Object> outcome(Map<String,Object> shipment,boolean success,String message){Map<String,Object> result=new LinkedHashMap<>();result.put("id",shipment.get("id"));result.put("shipment_sn",shipment.get("shipment_sn"));result.put("success",success);result.put("message",message);return result;}
    @SuppressWarnings("unchecked") private Map<String,Object> select(String company,Map<String,Object> shipment,String mode,String requested,String reason,String actor){
        if("AUTO".equals(mode)&&"WAIT_DISPATCH".equals(text(shipment,"status")))return outcome(shipment,true,"快递已确认，保留现有选择");
        Map<String,Object> configuration=config(company,number(shipment,"channel_id"),true),p=configuration==null?null:policy(configuration.get("policy_json"));
        String failure=configuration==null?"店铺快递规则未配置，请先配置允许快递":("AUTO".equals(mode)&&number(p,"enabled")!=1?"店铺自动匹配快递未启用":"");
        if(failure.isEmpty()&&text(shipment,"receiver_province").isEmpty())failure="收货省份缺失或无效，请核对来源订单地址";
        List<Map<String,Object>> all=carriers(company,true);Map<String,Map<String,Object>> byCode=new LinkedHashMap<>();for(Map<String,Object> c:all)byCode.put(text(c,"carrier_code"),c);
        Map<String,Object> selected=null;List<String> rejected=new ArrayList<>();
        if(failure.isEmpty()){
            List<String> candidates="MANUAL".equals(mode)?Arrays.asList(requested):OrderCarrierPolicy.ordered(p,shipment);
            for(String code:candidates){Map<String,Object> c=byCode.get(code);String unavailable=!((List<String>)p.get("carrier_order")).contains(code)?"不属于店铺允许快递":c==null?"快递资料不存在":OrderCarrierPolicy.unavailable(c,shipment);if(unavailable.isEmpty()){selected=c;break;}rejected.add(code+"："+unavailable);}
            if(selected==null)failure=rejected.isEmpty()?"店铺未配置允许快递":"无可用快递；"+String.join("；",rejected);
        }
        if(!failure.isEmpty()){
            require(!"MANUAL".equals(mode),failure);if(failure.length()>500)failure=failure.substring(0,500);
            repository.db().update("UPDATE oms_order_shipment SET carrier_match_status='FAILED',carrier_match_mode='AUTO',carrier_match_reason=?,carrier_attempt_at=NOW(),receiver_province=?,control_version=control_version+1 WHERE company_code=? AND id=?",failure,text(shipment,"receiver_province"),company,shipment.get("id"));log(company,shipment,"CARRIER_MATCH","FAILED",failure,actor);return outcome(shipment,false,failure);
        }
        Map<String,Object> service=OrderCarrierPolicy.service(selected,shipment),snapshot=new LinkedHashMap<>();snapshot.put("rule_revision",configuration.get("revision"));snapshot.put("carrier_revision",selected.get("revision"));snapshot.put("policy",p);snapshot.put("service",service);snapshot.put("receiver_province",shipment.get("receiver_province"));snapshot.put("rejected",rejected);
        String message="MANUAL".equals(mode)?reason:"按仓库、省份优先及默认顺序匹配";
        repository.db().update("UPDATE oms_order_shipment SET status='WAIT_DISPATCH',carrier_code=?,carrier_name=?,warehouse_carrier_code=?,carrier_match_status='MATCHED',carrier_match_mode=?,carrier_match_reason=?,carrier_attempt_at=NOW(),carrier_matched_at=NOW(),carrier_matched_by=?,carrier_snapshot=?,receiver_province=?,control_version=control_version+1 WHERE company_code=? AND id=?",selected.get("carrier_code"),selected.get("carrier_name"),service.get("warehouse_carrier_code"),mode,message,actor,encode(snapshot),text(shipment,"receiver_province"),company,shipment.get("id"));
        log(company,shipment,"MANUAL".equals(mode)?"CARRIER_ADJUST":"CARRIER_MATCH","SUCCESS",text(selected,"carrier_name")+"（"+text(selected,"carrier_code")+"）；"+message,actor);Map<String,Object> result=outcome(shipment,true,"已匹配 "+text(selected,"carrier_name"));result.put("carrier_code",selected.get("carrier_code"));return result;
    }
    /** Inside document creation's existing transaction; warehouse mapping was just validated by allocation. */
    public Map<String,Object> matchNew(String company,long shipment,long platform,String actor){Map<String,Object> row=repository.db().query("SELECT * FROM oms_order_shipment WHERE company_code=? AND id=? FOR UPDATE",ROW,company,shipment).get(0);row.put("platform_order_id",platform);return select(company,row,"AUTO","","",actor);}
    @SuppressWarnings("unchecked") public List<Map<String,Object>> match(String company,Map<String,Object> input,String actor){
        String mode=text(input,"mode");require(Arrays.asList("AUTO","MANUAL").contains(mode),"快递匹配方式无效");Object raw=input.get("items");require(raw instanceof List&&!((List<?>)raw).isEmpty()&&((List<?>)raw).size()<=20,"每次请选择1至20张发货单");require(!"MANUAL".equals(mode)||((List<?>)raw).size()==1,"手工调整每次只处理一张发货单");String reason=text(input,"reason").trim();require(!"MANUAL".equals(mode)||(!reason.isEmpty()&&reason.length()<=200&&text(input,"carrier_code").matches("[A-Za-z0-9_-]{1,64}")),"请选择快递并填写1至200字调整原因");
        Set<Long> seen=new HashSet<>();List<Map<String,Object>> requests=new ArrayList<>();
        for(Object item:(List<?>)raw){require(item instanceof Map,"发货单参数无效");Map<String,Object> request=(Map<String,Object>)item;long id=number(request,"id");require(id>0&&seen.add(id)&&request.containsKey("control_version")&&number(request,"control_version")>=0,"发货单ID重复或缺少版本，请刷新后重试");requests.add(request);}
        Set<String> active=warehouses(catalog.warehouses(company));List<Map<String,Object>> results=new ArrayList<>();
        for(Map<String,Object> request:requests){long id=number(request,"id");try{results.add(tx().execute(status->{Map<String,Object> shipment=lock(company,id,number(request,"control_version"));require(active.contains(OrderCarrierPolicy.warehouseKey(shipment)),"发货仓已停用或货主映射变化，请核对仓库资料");return select(company,shipment,mode,text(input,"carrier_code"),reason,actor);}));}catch(IllegalArgumentException e){Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("success",false);result.put("message",e.getMessage());results.add(result);}}
        return results;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> options(String company,long id){
        List<Map<String,Object>> shipments=repository.db().query("SELECT s.*,p.processing_payload FROM oms_order_shipment s JOIN oms_order o ON o.company_code=s.company_code AND o.id=s.order_id JOIN oms_platform_order p ON p.company_code=o.company_code AND p.id=o.platform_order_id WHERE s.company_code=? AND s.id=?",ROW,company,id);require(shipments.size()==1,"发货单不存在或无权访问");Map<String,Object> shipment=shipments.get(0);if(text(shipment,"receiver_province").isEmpty())shipment.put("receiver_province",receiverProvince(shipment));Map<String,Object> configuration=config(company,number(shipment,"channel_id"),false),p=configuration==null?null:policy(configuration.get("policy_json"));boolean warehouse=warehouses(catalog.warehouses(company)).contains(OrderCarrierPolicy.warehouseKey(shipment));List<Map<String,Object>> options=new ArrayList<>();
        for(Map<String,Object> carrier:carriers(company,false)){Map<String,Object> row=new LinkedHashMap<>();row.put("carrier_code",carrier.get("carrier_code"));row.put("carrier_name",carrier.get("carrier_name"));String reason=!warehouse?"发货仓已停用或映射变化":p==null?"店铺快递规则未配置":!((List<String>)p.get("carrier_order")).contains(text(carrier,"carrier_code"))?"不属于店铺允许快递":OrderCarrierPolicy.unavailable(carrier,shipment);row.put("available",reason.isEmpty());row.put("reason",reason);options.add(row);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("carriers",options);result.put("receiver_province",shipment.get("receiver_province"));return result;
    }
}
