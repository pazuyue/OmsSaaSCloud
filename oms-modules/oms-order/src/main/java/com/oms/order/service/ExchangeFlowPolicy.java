package com.oms.order.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** Merchant authority for automatic business decisions, independent of platform API permission. */
@Service public class ExchangeFlowPolicy {
    static final String ACTOR="自动换货流程",REVIEW_ACTOR="自动换货规则";
    static final List<String> FLAGS=Arrays.asList("auto_review","auto_stop","auto_agree","auto_return_create","auto_return_dispatch","auto_qc","auto_receive","auto_allocate","auto_carrier","auto_dispatch","auto_consign","auto_internal_review","auto_unshipped","auto_continue_review","auto_return_merge","auto_mixed_return_merge","auto_allocate_receipt","allow_original_recipient");
    @Resource OrderRepository repository;
    @Resource OrderCatalog catalog;
    static Map<String,Object> defaults(){Map<String,Object> p=new LinkedHashMap<>();for(String key:FLAGS)p.put(key,0);p.putAll(map("quantity_limit",0,"return_store","","platform_address_id","","return_address","","return_contact","","collect_seconds",120,"stock_wait_minutes",60,"return_wait_days",14,"warehouse_wait_minutes",10,"mappings",new ArrayList<>()));return p;}
    public Map<String,Object> read(String c,long channel){List<Map<String,Object>> rows=repository.db().query("SELECT enabled,revision,enabled_after,policy_json,updated_by,update_time FROM oms_exchange_flow_policy WHERE company_code=? AND channel_id=?",ROW,c,channel);Map<String,Object> p=defaults();if(!rows.isEmpty())p.putAll(object(rows.get(0).get("policy_json")));p.putAll(map("channel_id",channel,"enabled",rows.isEmpty()?0:rows.get(0).get("enabled"),"revision",rows.isEmpty()?0:rows.get(0).get("revision"),"enabled_after",rows.isEmpty()?null:rows.get(0).get("enabled_after")));return p;}
    static boolean on(Map<String,Object> p,String key){return number(p,key)==1;}
    void requireCurrent(String c,long channel,long revision,String flag){Map<String,Object> p=read(c,channel);require(on(p,"enabled")&&number(p,"revision")==revision&&(flag.isEmpty()||on(p,flag)),"换货自动规则已变化或关闭，请重新评估；已发命令继续核对");}
    public void save(String c,long channel,Map<String,Object> input,String actor){
        catalog.validateShop(c,channel);Map<String,Object> p=defaults();
        for(String key:FLAGS){long n=number(input,key);require(n==0||n==1,"自动换货开关无效："+key);p.put(key,n);}
        long enabled=number(input,"enabled"),limit=number(input,"quantity_limit");require((enabled==0||enabled==1)&&limit>=0&&limit<=1000,"自动换货数量上限须为0至1000的整数");require(!on(p,"auto_review")&&!on(p,"auto_internal_review")||limit>0,"自动确认方案须设置正数数量上限");p.put("quantity_limit",limit);
        for(String key:Arrays.asList("return_store","platform_address_id","return_address","return_contact")){String value=text(input,key).trim();require(value.length()<=(key.equals("return_address")?500:key.equals("return_contact")?200:64),"换货自动设置内容过长");p.put(key,value);}
        require(text(p,"platform_address_id").isEmpty()||text(p,"platform_address_id").matches("[1-9][0-9]{0,18}"),"平台换货退货地址编号无效");
        if(on(p,"auto_review")||on(p,"auto_internal_review")||on(p,"auto_return_create")){require(!text(p,"return_store").isEmpty(),"请设置自动换货退回仓");require(!text(p,"return_address").isEmpty()&&!text(p,"return_contact").isEmpty(),"请设置买家退回地址和联系人");}
        if(on(p,"auto_agree"))require(!text(p,"platform_address_id").isEmpty(),"自动同意换货须设置平台退货地址编号");
        bounded(p,input,"collect_seconds",0,3600,120);bounded(p,input,"stock_wait_minutes",1,10080,60);bounded(p,input,"return_wait_days",1,60,14);bounded(p,input,"warehouse_wait_minutes",1,1440,10);
        Object raw=input.get("mappings");require(raw==null||raw instanceof List,"换货SKU规则格式无效");List<?> given=raw==null?Collections.emptyList():(List<?>)raw;require(given.size()<=200,"每店最多200条明确SKU规则");List<Map<String,Object>> mappings=new ArrayList<>();Set<String> keys=new HashSet<>(),skus=new LinkedHashSet<>();
        for(Object item:given){require(item instanceof Map,"换货SKU规则无效");Map<String,Object> row=object(item);String from=text(row,"source_sku").trim(),target=text(row,"platform_target").trim(),sku=text(row,"target_sku").trim(),evidence=text(row,"no_surcharge_basis").trim();require(!from.isEmpty()&&from.length()<=128&&!sku.isEmpty()&&sku.length()<=128&&!target.isEmpty()&&target.length()<=1000,"请填写原SKU、平台目标编码或完整描述、换出SKU");require(!evidence.isEmpty()&&evidence.length()<=500,"每条自动换货规则须明确无需补差或免补差的商家依据");require(keys.add(from+"\n"+target),"相同原SKU及平台目标不可配置多个自动换出SKU");mappings.add(map("source_sku",from,"platform_target",target,"target_sku",sku,"no_surcharge_basis",evidence));skus.add(from);skus.add(sku);}
        if(!skus.isEmpty()){Map<String,List<Map<String,Object>>> goods=catalog.goods(c,new ArrayList<>(skus));for(String sku:skus){List<Map<String,Object>> found=goods.getOrDefault(sku,Collections.emptyList());require(found.size()==1&&number(found.get(0),"is_fd")==0&&number(found.get(0),"is_package")==0,"自动换货当前只允许有效普通SKU："+sku);}}
        require(enabled==0||!on(p,"auto_review")&&!on(p,"auto_internal_review")||!mappings.isEmpty(),"启用自动确认方案前请至少设置一条明确SKU映射");
        require(!on(p,"auto_mixed_return_merge")||on(p,"auto_return_merge"),"与退款共同退回须先启用换货合包");
        p.put("mappings",mappings);new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource())).execute(tx->{List<Long> old=repository.db().queryForList("SELECT revision FROM oms_exchange_flow_policy WHERE company_code=? AND channel_id=? FOR UPDATE",Long.class,c,channel);require(number(input,"revision")== (old.isEmpty()?0:old.get(0)),"换货自动规则已变化，请刷新后保存");repository.db().update("INSERT INTO oms_exchange_flow_policy(company_code,channel_id,enabled,enabled_after,policy_json,updated_by) VALUES (?,?,?,IF(?=1,NOW(),NULL),?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),enabled_after=IF(enabled_after IS NULL AND VALUES(enabled)=1,NOW(),enabled_after),policy_json=VALUES(policy_json),updated_by=VALUES(updated_by),revision=revision+1",c,channel,enabled,enabled,encode(p),actor);return null;});
    }
    private void bounded(Map<String,Object> p,Map<String,Object> input,String key,long min,long max,long initial){long value=input.containsKey(key)?number(input,key):initial;require(value>=min&&value<=max,"自动换货等待参数超出范围："+key);p.put(key,value);}
}
