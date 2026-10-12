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
    @SuppressWarnings("unchecked") private Map<String,Object> policy(Object json){try{return JSON.readValue(String.valueOf(json),Map.class);}catch(Exception e){throw new IllegalArgumentException("分仓规则未配置或已损坏");}}
    private Map<String,Object> defaultPolicy(){
        Map<String,Object> defaults=new LinkedHashMap<>();for(String flag:Arrays.asList("enabled","allow_multi","allow_partial","allow_split_sku"))defaults.put(flag,0);defaults.put("owner_code","");defaults.put("warehouse_order",Collections.emptyList());defaults.put("province_priorities",Collections.emptyMap());return defaults;
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
    }
    private Map<String,Object> lock(String company,long id){
        List<Map<String,Object>> keys=repository.db().query("SELECT channel_id,platform_order_id FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(keys.size()==1,"业务订单不存在或无权访问");
        List<Map<String,Object>> shops=repository.db().query("SELECT lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,keys.get(0).get("channel_id"));require(shops.size()==1,"店铺配置不存在");require(shops.get(0).get("lease_until")==null||time(text(shops.get(0),"lease_until")).isBefore(LocalDateTime.now()),"店铺正在处理，请稍后重试");
        Map<String,Object> platform=repository.db().query("SELECT change_pending,platform_status,fulfillment_block_reason,business_order_id,conversion_status,processing_payload FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,keys.get(0).get("platform_order_id")).get(0);
        Map<String,Object> order=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);order.putAll(platform);
        require("WAIT_ALLOCATION".equals(text(order,"status")),"当前业务阶段不允许分仓");require(number(order,"manual_hold")==0,"订单已人工挂起");require("APPROVED".equals(text(order,"review_status")),"订单尚未审核通过");
        String block=OrderReviewPolicy.systemBlock(order);require(block.isEmpty(),block);require(number(platform,"business_order_id")==id&&"SUCCESS".equals(text(platform,"conversion_status")),"业务订单关联状态不一致");return order;
    }
    @SuppressWarnings("unchecked") public List<Map<String,Object>> allocate(String company,Map<String,Object> input,String actor){
        require(input.get("items") instanceof List,"请选择业务订单");List<?> items=(List<?>)input.get("items");require(!items.isEmpty()&&items.size()<=20,"每次请选择1至20张订单");
        List<Map<String,Object>> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(Object value:items){require(value instanceof Map,"订单参数无效");Map<String,Object> requested=(Map<String,Object>)value;require(requested.containsKey("control_version"),"请刷新订单后重试");long id=number(requested,"id");require(id>0&&seen.add(id),"订单ID无效或重复");Map<String,Object> outcome=new LinkedHashMap<>();outcome.put("id",id);boolean started=false;
            try{
                // Persist the intent before the remote inventory transaction. A timeout/crash can be retried.
                tx().execute(status->{Map<String,Object> order=lock(company,id);require(number(order,"control_version")==number(requested,"control_version"),"订单已变化，请刷新后重试");require(!"FULL".equals(text(order,"allocation_status")),"订单已全部分仓，无需重复提交");
                    String snapshot=text(order,"allocation_policy");
                    if(!snapshot.isEmpty()&&number(order,"allocation_quantity")==0){List<Map<String,Object>> previous=data(warehouse.receipts(company,number(order,"channel_id"),text(order,"platform_tid"),SecurityConstants.INNER));if(previous.isEmpty())snapshot="";}
                    if(snapshot.isEmpty()){
                        List<Map<String,Object>> configs=repository.db().query("SELECT policy_json FROM oms_order_allocation_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,order.get("channel_id"));require(configs.size()==1,"店铺分仓规则未配置");Map<String,Object> p=policy(configs.get(0).get("policy_json"));require(number(p,"enabled")==1,"店铺分仓未启用");
                        String province="";try{province=JSON.readTree(text(order,"processing_payload")).path("trade_fullinfo_get_response").path("trade").path("receiver_state").asText("");}catch(Exception e){throw new IllegalArgumentException("订单省份数据无法读取");}
                        p.put("receiver_province",province);p.put("resolved_order",OrderAllocationPolicy.ordered(p,province));snapshot=encode(p);
                    }
                    repository.db().update("UPDATE oms_order SET allocation_policy=?,allocation_status='PROCESSING',allocation_error='' WHERE company_code=? AND id=?",snapshot,company,id);return null;});started=true;
                tx().execute(status->{Map<String,Object> order=lock(company,id);require(number(order,"control_version")==number(requested,"control_version"),"订单已变化，请刷新后重试");Map<String,Object> p=policy(order.get("allocation_policy"));
                    Map<String,Map<String,Object>> byCode=new HashMap<>();for(Map<String,Object> store:catalog.warehouses(company))byCode.put(text(store,"wms_simulation_code"),store);
                    List<Map<String,Object>> stores=new ArrayList<>();for(String code:(List<String>)p.get("resolved_order")){Map<String,Object> store=byCode.get(code);require(store!=null&&text(p,"owner_code").equals(text(store,"owner_code")),"允许仓已停用或货主映射变化，请核对仓库资料");Map<String,Object> mapped=new LinkedHashMap<>(store);mapped.put("store_code",code);stores.add(mapped);}
                    Map<String,Object> command=new LinkedHashMap<>(p);command.put("stores",stores);command.put("channel_id",order.get("channel_id"));command.put("platform_tid",order.get("platform_tid"));
                    List<Map<String,Object>> lines=repository.db().query("SELECT * FROM oms_order_line WHERE company_code=? AND order_id=? ORDER BY id",ROW,company,id);command.put("lines",lines);
                    R<List<Map<String,Object>>> response=warehouse.allocate(company,command,SecurityConstants.INNER);require(response!=null&&R.isSuccess(response),response==null?"分仓服务未响应，请重试核对结果":response.getMsg());List<Map<String,Object>> receipts=response.getData();require(receipts!=null,"分仓结果为空，请重试核对结果");
                    long qty=receipts.stream().mapToLong(r->number(r,"quantity")).sum(),required=lines.stream().mapToLong(r->number(r,"quantity")).sum();require(qty>0&&qty<=required,"分仓回执数量异常");String stage=qty==required?"FULL":"PART";
                    List<Map<String,Object>> shipments=new OrderShipmentService(repository).create(company,order,lines,receipts,actor);outcome.put("shipments",shipments);
                    repository.db().update("UPDATE oms_order SET allocation_status=?,allocation_quantity=?,allocation_receipts=?,allocation_error='',allocation_at=NOW(),control_version=control_version+1 WHERE company_code=? AND id=?",stage,qty,encode(receipts),company,id);
                    repository.log(company,number(order,"platform_order_id"),"WAREHOUSE_ALLOCATE",stage.equals("FULL")?"SUCCESS":"PART", "已分仓 "+qty+" / "+required+" 件；"+(stage.equals("FULL")?"等待下发仓库":"余量等待补货后继续分仓"),actor);
                    outcome.put("allocation_status",stage);outcome.put("quantity",qty);outcome.put("message",stage.equals("FULL")?"全部分仓完成，待下发仓库":"已部分分仓，余量待补货");return null;});outcome.put("success",true);
            }catch(RuntimeException e){String message=e instanceof IllegalArgumentException?e.getMessage():"分仓结果暂未确认，请重试核对；重复提交不会重复占库存";if(message==null)message="分仓失败，请刷新重试";if(message.length()>500)message=message.substring(0,500);final String error=message;
                if(started)repository.db().update("UPDATE oms_order SET allocation_status=IF(?='UNCERTAIN','UNCERTAIN',IF(allocation_quantity>0,'PART','FAILED')),allocation_error=? WHERE company_code=? AND id=? AND status='WAIT_ALLOCATION' AND allocation_status<>'FULL' AND control_version=?",e instanceof IllegalArgumentException?"FAILED":"UNCERTAIN",error,company,id,number(requested,"control_version"));
                outcome.remove("shipments");outcome.put("success",false);outcome.put("message",message);
            }result.add(outcome);
        }return result;
    }
}
