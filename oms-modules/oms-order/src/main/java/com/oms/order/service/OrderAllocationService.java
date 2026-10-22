package com.oms.order.service;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

@Service
public class OrderAllocationService {
    @Resource private OrderRepository repository;
    @Resource private OrderCatalog catalog;
    @Resource private OrderWarehouseClient warehouse;
    private TransactionTemplate tx(){TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));tx.setTimeout(60);return tx;}
    @SuppressWarnings("unchecked") private Map<String,Object> policy(Object json){try{return OrderAllocationPolicy.defaults(JSON.readValue(String.valueOf(json),Map.class));}catch(Exception e){throw new IllegalArgumentException("分仓规则未配置或已损坏");}}
    private Map<String,Object> defaultPolicy(){
        Map<String,Object> defaults=new LinkedHashMap<>();for(String flag:Arrays.asList("enabled","allow_multi","allow_partial","allow_split_sku"))defaults.put(flag,0);defaults.put("owner_code","");defaults.put("warehouse_order",Collections.emptyList());defaults.put("province_priorities",Collections.emptyMap());return OrderAllocationPolicy.defaults(defaults);
    }
    public List<Map<String,Object>> configurations(String company){
        List<Map<String,Object>> rows=repository.db().query("SELECT c.channel_id,c.channel_name,a.policy_json,a.revision FROM oms_order_pull_config c LEFT JOIN oms_order_allocation_config a ON a.company_code=c.company_code AND a.channel_id=c.channel_id WHERE c.company_code=? ORDER BY c.channel_id",ROW,company);
        for(Map<String,Object> row:rows){Object json=row.remove("policy_json");row.put("configured",json!=null);row.put("policy",json==null?defaultPolicy():policy(json));if(row.get("revision")==null)row.put("revision",0);}return rows;
    }
    public Map<String,Object> configuration(String company,long channel){
        Map<String,Object> result=new LinkedHashMap<>();List<Map<String,Object>> found=repository.db().query("SELECT * FROM oms_order_allocation_config WHERE company_code=? AND channel_id=?",ROW,company,channel);
        Map<String,Object> defaults=defaultPolicy();
        result.put("policy",found.isEmpty()?defaults:policy(found.get(0).get("policy_json")));result.put("revision",found.isEmpty()?0:found.get(0).get("revision"));result.put("channel_id",channel);result.put("warehouses",catalog.warehouses(company));return result;
    }
    @SuppressWarnings("unchecked") public void save(String company,Map<String,Object> input,String actor){
        long channel=number(input,"channel_id");catalog.validateShop(company,channel);require(input.get("policy") instanceof Map,"分仓规则无效");
        Map<String,Object> normalized=OrderAllocationPolicy.validate((Map<String,Object>)input.get("policy"),catalog.warehouses(company));
        tx().execute(status->{require(repository.db().query("SELECT id FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel).size()==1,"请先保存店铺接入配置");
            List<Map<String,Object>> old=repository.db().query("SELECT revision FROM oms_order_allocation_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel);
            require((old.isEmpty()?0:number(old.get(0),"revision"))==number(input,"revision"),"分仓规则已变化，请刷新后重试");
            repository.db().update("INSERT INTO oms_order_allocation_config(company_code,channel_id,policy_json,updated_by) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE policy_json=VALUES(policy_json),updated_by=VALUES(updated_by),revision=revision+1",company,channel,encode(normalized),actor);return null;});
        OrderAutoAllocationService.configurationChanged(repository.db(),company,channel);
    }
    private Map<String,Object> lock(String company,long id){return read(company,id,true);}
    private Map<String,Object> read(String company,long id,boolean locked){String suffix=locked?" FOR UPDATE":"";
        List<Map<String,Object>> keys=repository.db().query("SELECT channel_id,platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(keys.size()==1,"业务订单不存在或无权访问");
        List<Map<String,Object>> shops=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=?"+suffix,ROW,company,keys.get(0).get("channel_id"));require(shops.size()==1,"店铺配置不存在");require(shops.get(0).get("lease_until")==null||time(text(shops.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺正在处理，请稍后重试");
        Map<String,Object> platform=repository.db().query("SELECT change_pending,platform_status,fulfillment_block_reason,business_order_id,conversion_status,processing_payload FROM oms_platform_order WHERE company_code=? AND id=?"+suffix,ROW,company,keys.get(0).get("platform_order_id")).get(0);
        Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=?"+suffix,ROW,company,id).get(0);order.putAll(platform);
        if(locked&&repository.gifts()!=null)repository.gifts().guardCurrent(company,order);OrderExchangeGuard.requireReady(repository,company,id,locked);require("WAIT_ALLOCATION".equals(text(order,"status")),"当前业务阶段不允许分仓");require(number(order,"manual_hold")==0,"订单已人工挂起");require("APPROVED".equals(text(order,"review_status")),"订单尚未审核通过");
        String block=OrderReviewPolicy.systemBlock(order);require(block.isEmpty(),block);require(number(platform,"business_order_id")==id&&"SUCCESS".equals(text(platform,"conversion_status")),"业务订单关联状态不一致");return order;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> resolvedPolicy(String company,Map<String,Object> order,boolean locked){
        String snapshot=text(order,"allocation_policy");if(!snapshot.isEmpty()&&number(order,"allocation_quantity")==0){List<Map<String,Object>> previous=data(warehouse.receipts(company,number(order,"channel_id"),stockReference(order),SecurityConstants.INNER));if(previous.isEmpty())snapshot="";}
        if(!snapshot.isEmpty()){order.put("using_allocation_snapshot",true);return policy(snapshot);}order.put("using_allocation_snapshot",false);
        List<Map<String,Object>> configs=repository.db().query("SELECT policy_json,revision FROM oms_order_allocation_config WHERE company_code=? AND channel_id=?"+(locked?" FOR UPDATE":""),ROW,company,order.get("channel_id"));require(configs.size()==1,"店铺分仓规则未配置");Map<String,Object> p=policy(configs.get(0).get("policy_json"));require(number(p,"enabled")==1,"店铺分仓未启用");
        String province="";try{province=JSON.readTree(text(order,"processing_payload")).path("trade_fullinfo_get_response").path("trade").path("receiver_state").asText("");}catch(Exception e){throw new IllegalArgumentException("订单省份数据无法读取");}
        p.put("rule_revision",configs.get(0).get("revision"));p.put("receiver_province",province);p.put("resolved_order",OrderAllocationPolicy.ordered(p,province));return p;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> command(String company,Map<String,Object> order,Map<String,Object> policy,List<Map<String,Object>> catalog){
        Map<String,Map<String,Object>> byCode=new HashMap<>();for(Map<String,Object> store:catalog)byCode.put(text(store,"wms_simulation_code"),store);List<Map<String,Object>> stores=new ArrayList<>();
        for(String code:(List<String>)policy.get("resolved_order")){Map<String,Object> store=byCode.get(code);require(store!=null&&text(policy,"owner_code").equals(text(store,"owner_code")),"允许仓已停用或货主映射变化，请核对仓库资料");Map<String,Object> mapped=new LinkedHashMap<>(store);mapped.put("store_code",code);stores.add(mapped);}
        Map<String,Object> command=new LinkedHashMap<>(policy);command.put("stores",stores);command.put("channel_id",order.get("channel_id"));command.put("platform_tid",stockReference(order));command.put("lines",repository.db().query("SELECT * FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type=? AND stock_reference=? ORDER BY id",ROW,company,order.get("id"),"EXCHANGE".equals(text(order,"business_type"))?"EXCHANGE":"SALE",text(order,"stock_reference")));
        command.put("global_fulfillment",repository.db().query("SELECT DISTINCT l.sku_sn,l.store_code FROM oms_order_shipment_line l JOIN oms_order_shipment s ON s.company_code=l.company_code AND s.id=l.shipment_id WHERE s.company_code=? AND s.order_id=? AND s.business_type=? AND (?<>'EXCHANGE' OR s.stock_reference=?) AND s.status<>'VOIDED'",ROW,company,order.get("id"),"EXCHANGE".equals(text(order,"business_type"))?"EXCHANGE":"SALE",text(order,"business_type"),text(order,"stock_reference")));command.put("existing_shipment_count",repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment WHERE company_code=? AND order_id=? AND business_type=? AND (?<>'EXCHANGE' OR stock_reference=?) AND status<>'VOIDED'",Long.class,company,order.get("id"),"EXCHANGE".equals(text(order,"business_type"))?"EXCHANGE":"SALE",text(order,"business_type"),text(order,"stock_reference")));
        command.put("mapped_allocation_ids",repository.db().queryForList("SELECT l.allocation_id FROM oms_order_shipment_line l JOIN oms_order_shipment s ON s.company_code=l.company_code AND s.id=l.shipment_id JOIN oms_order_line ol ON ol.company_code=l.company_code AND ol.id=l.order_line_id WHERE l.company_code=? AND s.order_id=? AND COALESCE(NULLIF(ol.stock_reference,''),NULLIF(s.stock_reference,''),s.platform_tid)=? ORDER BY l.allocation_id",Long.class,company,order.get("id"),stockReference(order)));return command;
    }
    @SuppressWarnings("unchecked") public List<Map<String,Object>> preview(String company,Map<String,Object> input){
        require(input.get("items") instanceof List,"请选择业务订单");List<?> items=(List<?>)input.get("items");require(!items.isEmpty()&&items.size()<=20,"每次请选择1至20张订单");List<Map<String,Object>> results=new ArrayList<>(),stores=catalog.warehouses(company);Set<Long> seen=new HashSet<>();
        for(Object value:items){require(value instanceof Map,"订单参数无效");Map<String,Object> requested=(Map<String,Object>)value;long id=number(requested,"id");require(id>0&&seen.add(id),"订单ID无效或重复");Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);
            try{require(requested.containsKey("control_version"),"请刷新订单后重新预览");Map<String,Object> order=read(company,id,false);require(number(order,"control_version")==number(requested,"control_version"),"订单已变化，请刷新后重试");require(!"FULL".equals(text(order,"allocation_status")),"订单已全部分仓");Map<String,Object> p=resolvedPolicy(company,order,false);result.putAll(previewScopes(company,order,p,stores));result.put("policy",p);result.put("policy_fingerprint",com.oms.common.util.AllocationDigest.hash(p));result.put("control_version",order.get("control_version"));result.put("using_snapshot",Boolean.TRUE.equals(order.get("using_allocation_snapshot")));
            }catch(RuntimeException e){result.put("can_allocate",false);result.put("retry_kind",e instanceof IllegalArgumentException?"MANUAL":"SYSTEM");result.put("message",e instanceof IllegalArgumentException?e.getMessage():"预览暂未完成，请稍后重试");}results.add(result);
        }return results;
    }
    public List<Map<String,Object>> allocate(String company,Map<String,Object> input,String actor){return allocate(company,input,actor,"");}
    List<Map<String,Object>> allocateAutomatic(String company,Map<String,Object> input,String lease){return allocate(company,input,"系统自动分仓",lease);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> allocate(String company,Map<String,Object> input,String actor,String automaticLease){
        require(input.get("items") instanceof List,"请选择业务订单");List<?> items=(List<?>)input.get("items");require(!items.isEmpty()&&items.size()<=20,"每次请选择1至20张订单");
        List<Map<String,Object>> result=new ArrayList<>(),warehouses=catalog.warehouses(company);Set<Long> seen=new HashSet<>();
        for(Object value:items){require(value instanceof Map,"订单参数无效");Map<String,Object> requested=(Map<String,Object>)value;require(requested.containsKey("control_version"),"请刷新订单后重试");long id=number(requested,"id");require(id>0&&seen.add(id),"订单ID无效或重复");Map<String,Object> outcome=new LinkedHashMap<>();outcome.put("id",id);boolean started=false;
            try{
                // Persist the intent before the remote inventory transaction. A timeout/crash can be retried.
                tx().execute(status->{Map<String,Object> order=lock(company,id);if(!automaticLease.isEmpty())OrderAutoAllocationService.guard(repository.db(),company,order,automaticLease);require(number(order,"control_version")==number(requested,"control_version"),"订单已变化，请刷新后重试");require(!"FULL".equals(text(order,"allocation_status")),"订单已全部分仓，无需重复提交");
                    Map<String,Object> selectedPolicy=resolvedPolicy(company,order,true);if(requested.containsKey("policy_fingerprint"))require(com.oms.common.util.AllocationDigest.hash(selectedPolicy).equals(text(requested,"policy_fingerprint")),"分仓规则已变化，请重新预览后确认");String snapshot=encode(selectedPolicy);
                    repository.db().update("UPDATE oms_order SET allocation_policy=?,allocation_status='PROCESSING',allocation_error='' WHERE company_code=? AND id=?",snapshot,company,id);return null;});started=true;
                tx().execute(status->{Map<String,Object> order=lock(company,id);if(!automaticLease.isEmpty())OrderAutoAllocationService.guard(repository.db(),company,order,automaticLease);require(number(order,"control_version")==number(requested,"control_version"),"订单已变化，请刷新后重试");Map<String,Object> p=policy(order.get("allocation_policy"));
                    List<Map<String,Object>> allLines=OrderFulfillmentLines.sale(repository,company,id),receipts=new ArrayList<>(),shipments=new ArrayList<>();
                    Map<String,String> fingerprints=new HashMap<>();
                    Map<String,List<Map<String,Object>>> groups=OrderFulfillmentLines.groups(order,allLines);
                    if(requested.containsKey("plan_fingerprint")&&groups.size()>1){Map<String,Object> check=previewScopes(company,order,p,warehouses);require(text(check,"plan_fingerprint").equals(text(requested,"plan_fingerprint")),"库存或分仓结果已变化，请重新预览后确认");for(Map<String,Object> scope:(List<Map<String,Object>>)check.get("scope_plans"))fingerprints.put(text(scope,"reference"),text(scope,"plan_fingerprint"));}
                    for(Map.Entry<String,List<Map<String,Object>>> group:groups.entrySet()){
                        if(OrderFulfillmentLines.required(group.getValue())==0)continue;
                        Map<String,Object> context=OrderFulfillmentLines.context(order,group.getKey());context.put("exchange_id",group.getValue().get(0).get("exchange_id"));
                        Map<String,Object> command=command(company,context,p,warehouses);
                        if(groups.size()==1&&requested.containsKey("plan_fingerprint"))command.put("expected_plan_fingerprint",requested.get("plan_fingerprint"));
                        if(fingerprints.containsKey(group.getKey()))command.put("expected_plan_fingerprint",fingerprints.get(group.getKey()));
                        R<List<Map<String,Object>>> response=warehouse.allocate(company,command,SecurityConstants.INNER);require(response!=null&&R.isSuccess(response),response==null?"分仓服务未响应，请重试核对结果":response.getMsg());require(response.getData()!=null,"分仓结果为空，请重试核对结果");
                        receipts.addAll(response.getData());
                    }
                    if(!receipts.isEmpty())shipments.addAll(new OrderShipmentService(repository).create(company,order,allLines,receipts,actor));
                    long qty=receipts.stream().mapToLong(r->number(r,"quantity")).sum(),required=OrderFulfillmentLines.required(allLines);require(qty>0&&qty<=required,"分仓回执数量异常");String stage=qty==required?"FULL":"PART";
                    outcome.put("shipments",shipments);
                    repository.db().update("UPDATE oms_order SET cutoff_state=IF(cutoff_state='REPLAN_READY','COMPLETED',cutoff_state),allocation_status=?,allocation_quantity=?,allocation_receipts=?,allocation_error='',allocation_at=NOW(),control_version=control_version+1 WHERE company_code=? AND id=?",stage,qty,encode(receipts),company,id);
                    repository.log(company,number(order,"platform_order_id"),"WAREHOUSE_ALLOCATE",stage.equals("FULL")?"SUCCESS":"PART", "已分仓 "+qty+" / "+required+" 件；"+(stage.equals("FULL")?"等待下发仓库":"余量等待补货后继续分仓"),actor);
                    outcome.put("allocation_status",stage);outcome.put("quantity",qty);outcome.put("message",stage.equals("FULL")?"全部分仓完成，待下发仓库":"已部分分仓，余量待补货");return null;});outcome.put("success",true);
            }catch(RuntimeException e){String message=e instanceof IllegalArgumentException?e.getMessage():"分仓结果暂未确认，请重试核对；重复提交不会重复占库存";if(message==null)message="分仓失败，请刷新重试";if(message.length()>500)message=message.substring(0,500);final String error=message;
                if(started)repository.db().update("UPDATE oms_order SET allocation_status=IF(?='UNCERTAIN','UNCERTAIN',IF(allocation_quantity>0,'PART','FAILED')),allocation_error=? WHERE company_code=? AND id=? AND status='WAIT_ALLOCATION' AND allocation_status<>'FULL' AND control_version=?",e instanceof IllegalArgumentException?"FAILED":"UNCERTAIN",error,company,id,number(requested,"control_version"));
                outcome.remove("shipments");outcome.put("success",false);outcome.put("retry_kind",message.contains("请重新预览")||message.contains("订单已变化")||message.contains("店铺正在处理")?"CHANGED":e instanceof IllegalArgumentException?"MANUAL":"SYSTEM");outcome.put("message",message);
            }result.add(outcome);
        }return result;
    }

    boolean recoverAutomatic(String company,long id,String lease){return tx().execute(status->{
        Map<String,Object> order=lock(company,id);OrderAutoAllocationService.guard(repository.db(),company,order,lease);
        List<Map<String,Object>> lines=OrderFulfillmentLines.sale(repository,company,id),receipts=new ArrayList<>();
        for(Map.Entry<String,List<Map<String,Object>>> group:OrderFulfillmentLines.groups(order,lines).entrySet()){
            if(OrderFulfillmentLines.required(group.getValue())==0)continue;
            List<Map<String,Object>> current=data(warehouse.receipts(company,number(order,"channel_id"),group.getKey(),SecurityConstants.INNER));receipts.addAll(current);
        }
        if(!receipts.isEmpty())new OrderShipmentService(repository).create(company,order,lines,receipts,"系统自动分仓核对");
        long quantity=receipts.stream().mapToLong(r->number(r,"quantity")).sum();if(quantity<=number(order,"allocation_quantity"))return false;
        require(!text(order,"allocation_policy").isEmpty(),"已提交分仓回执缺少原规则，请人工核对");long required=OrderFulfillmentLines.required(lines);require(quantity<=required,"累计分仓回执超过订单数量");
        repository.db().update("UPDATE oms_order SET cutoff_state=IF(cutoff_state='REPLAN_READY','COMPLETED',cutoff_state),allocation_status=?,allocation_quantity=?,allocation_receipts=?,allocation_error='',allocation_at=NOW(),control_version=control_version+1 WHERE company_code=? AND id=?",quantity==required?"FULL":"PART",quantity,encode(receipts),company,id);
        repository.log(company,number(order,"platform_order_id"),"AUTO_ALLOCATE_RECOVER","SUCCESS","已核对原分仓回执并补建发货单，本次未新增库存占用","系统自动分仓");return true;
    });}
    @SuppressWarnings("unchecked") private Map<String,Object> previewScopes(String company,Map<String,Object> order,Map<String,Object> p,List<Map<String,Object>> stores){
        List<Map<String,Object>> scopes=new ArrayList<>(),lines=new ArrayList<>(),candidates=new ArrayList<>();Map<String,Object> out=new LinkedHashMap<>();boolean allowed=true;String message="";
        for(Map.Entry<String,List<Map<String,Object>>> group:OrderFulfillmentLines.groups(order,OrderFulfillmentLines.sale(repository,company,number(order,"id"))).entrySet()){
            if(OrderFulfillmentLines.required(group.getValue())==0)continue;
            Map<String,Object> report=data(warehouse.preview(company,command(company,OrderFulfillmentLines.context(order,group.getKey()),p,stores),SecurityConstants.INNER));report.put("reference",group.getKey());scopes.add(report);
            if(!Boolean.TRUE.equals(report.get("can_allocate"))&&number(report,"remaining_quantity")>0){allowed=false;message=text(report,"message");}
        }
        require(!scopes.isEmpty(),"没有有效待发需求");if(scopes.size()==1){out.putAll(scopes.get(0));out.put("scope_plans",scopes);return out;}
        for(String field:Arrays.asList("required_quantity","allocated_quantity","planned_quantity","remaining_quantity","logical_pending_quantity","recovered_quantity"))out.put(field,scopes.stream().mapToLong(r->number(r,field)).sum());
        for(Map<String,Object> scope:scopes){if(scope.get("lines") instanceof List)lines.addAll((List<Map<String,Object>>)scope.get("lines"));if(scope.get("candidates") instanceof List)candidates.addAll((List<Map<String,Object>>)scope.get("candidates"));}
        Set<String> destinations=new HashSet<>();Map<String,List<Map<String,Object>>> owned=OrderFulfillmentLines.groups(order,OrderFulfillmentLines.sale(repository,company,number(order,"id")));
        for(Map<String,Object> scope:scopes){List<Map<String,Object>> pending=new ArrayList<>((List<Map<String,Object>>)scope.getOrDefault("lines",Collections.emptyList()));pending.addAll((List<Map<String,Object>>)scope.getOrDefault("recovered_lines",Collections.emptyList()));Map<String,Object> identity=owned.get(text(scope,"reference")).get(0);for(Map<String,Object> line:pending)destinations.add(encode(Arrays.asList(text(line,"owner_code"),text(line,"real_store_code"),text(line,"store_code"),identity.get("exchange_id"),Math.max(1,number(identity,"fulfillment_round")))));}
        long existing=scopes.stream().mapToLong(r->number(r,"existing_shipment_count")).max().orElse(0);out.put("existing_shipment_count",existing);out.put("new_shipment_count",destinations.size());out.put("total_shipment_count",existing+destinations.size());if(number(p,"max_shipments")>0&&existing+destinations.size()>number(p,"max_shipments")){allowed=false;message="累计发货单数量超过本单规则上限，请核对仓库及履约批次";}
        out.put("lines",lines);out.put("candidates",candidates);out.put("can_allocate",allowed&&number(out,"planned_quantity")+number(out,"recovered_quantity")>0);out.put("message",message.isEmpty()?"按原单各次商品履约来源分仓，原始来源独立保留":message);out.put("scope_plans",scopes);out.put("plan_fingerprint",hash(encode(scopes)));out.put("retry_kind",allowed?"NONE":"MANUAL");return out;
    }

    @SuppressWarnings("unchecked") public Map<String,Object> allocateExchange(String company,long id,long version,String actor){
        List<Map<String,Object>> stores=catalog.warehouses(company);
        return tx().execute(s->{Map<String,Object> e=repository.db().query("SELECT * FROM oms_exchange WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("换货单不存在"));
            require(number(e,"control_version")==version,"换货单已变化，请刷新");require(!ExchangeFulfillment.unshipped(e)&&e.get("fulfillment_line_id")!=null,"换货单尚未生成换出明细");ExchangeFulfillment.planUnchanged(e);
            Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,e.get("order_id")).get(0),platform=repository.raw(company,number(order,"platform_order_id"));
            Map<String,Object> marker=ExchangeService.map("business_type","EXCHANGE","exchange_id",id);OrderShipmentOwner.apply(repository,company,marker,order,platform,true);require(number(order,"manual_hold")==0,"原订单已挂起，请先核对后再分仓换出");order.putAll(platform);order.put("id",e.get("order_id"));order.put("status","WAIT_ALLOCATION");order.put("business_type","EXCHANGE");order.put("exchange_id",id);order.put("stock_reference",ExchangeRounds.reference(e));order.put("fulfillment_round",ExchangeRounds.numberOf(e));order.put("processing_payload",platform.get("processing_payload"));
            Map<String,Object> receiver=ExchangeRounds.recipient(e);order.put("receiver_name",receiver.get("name"));order.put("receiver_phone",receiver.get("mobile"));order.put("receiver_address",String.join(" ",text(receiver,"province"),text(receiver,"city"),text(receiver,"area"),text(receiver,"detailAddress")));order.put("oaid",receiver.get("oaid"));
            order.put("allocation_policy",e.get("allocation_policy"));order.put("allocation_quantity",e.get("allocation_quantity"));Map<String,Object> p=e.get("allocation_policy")==null?resolvedPolicy(company,order,true):policy(e.get("allocation_policy"));order.put("allocation_policy",encode(p));
            Map<String,Object> command=command(company,order,p,stores);List<Map<String,Object>> lines=(List<Map<String,Object>>)command.get("lines");List<Map<String,Object>> receipts=data(warehouse.allocate(company,command,SecurityConstants.INNER));require(receipts!=null&&!receipts.isEmpty(),"换出分仓尚未获得占用回执");
            List<Map<String,Object>> shipments=new OrderShipmentService(repository).create(company,order,lines,receipts,actor);long qty=receipts.stream().mapToLong(r->number(r,"quantity")).sum();repository.db().update("UPDATE oms_exchange SET allocation_status=?,allocation_quantity=?,allocation_receipts=?,allocation_policy=?,control_version=control_version+1 WHERE company_code=? AND id=?",qty==ExchangeRounds.quantityOf(e)?"FULL":"PART",qty,encode(receipts),encode(p),company,id);return ExchangeService.map("shipments",shipments,"allocation_quantity",qty);
        });
    }

}
