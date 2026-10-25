package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;

/** Permission-scoped, read-only settings projection. Batch local reads; no vendor or warehouse probe. */
@Service public class OrderBusinessConfiguration {
    @Resource OrderRepository repository;
    @Resource OrderCatalog catalog;
    @Resource ConfiguredPushReader sources;
    static boolean can(Set<String> permissions,String permission){return permissions.contains("*:*:*")||permissions.contains(permission);}
    private Map<Long,Map<String,Object>> batch(String c,String sql,Long channel){Map<Long,Map<String,Object>> result=new HashMap<>();for(Map<String,Object> row:repository.db().query(sql+(channel==null?"":" AND c.channel_id=?"),ROW,channel==null?new Object[]{c}:new Object[]{c,channel}))result.put(number(row,"channel_id"),row);return result;}
    private Map<Long,Map<String,Object>> rows(String c,String table,String fields,Long channel){return batch(c,"SELECT "+fields+" FROM "+table+" c WHERE c.company_code=?",channel);}
    public Map<String,Object> listing(String c,Set<String> permissions,Map<String,String> query){
        int page,size;try{page=Integer.parseInt(query.getOrDefault("pageNum","1"));size=Integer.parseInt(query.getOrDefault("pageSize","20"));}catch(NumberFormatException bad){throw new IllegalArgumentException("分页参数无效");}
        require(page>=1&&page<=1000000&&size>=1&&size<=50,"分页参数无效");String keyword=query.getOrDefault("keyword","").trim().toLowerCase(Locale.ROOT),status=query.getOrDefault("status","");require(keyword.length()<=100&&Arrays.asList("","UNCONFIGURED","INCOMPLETE","AUTOMATIC","ERROR").contains(status),"配置筛选无效");
        List<Map<String,Object>> all=project(c,permissions,null),filtered=new ArrayList<>();long unset=0,incomplete=0,auto=0,error=0,total=0;
        for(Map<String,Object> row:all){if(!keyword.isEmpty()&&!text(row,"channel_name").toLowerCase(Locale.ROOT).contains(keyword)&&!String.valueOf(row.get("channel_id")).contains(keyword))continue;total++;if(!Boolean.TRUE.equals(row.get("configured")))unset++;if(!((List<?>)row.get("issues")).isEmpty())incomplete++;if(Boolean.TRUE.equals(row.get("automatic")))auto++;if(Boolean.TRUE.equals(row.get("sync_error")))error++;
            if("UNCONFIGURED".equals(status)&&Boolean.TRUE.equals(row.get("configured"))||"INCOMPLETE".equals(status)&&((List<?>)row.get("issues")).isEmpty()||"AUTOMATIC".equals(status)&&!Boolean.TRUE.equals(row.get("automatic"))||"ERROR".equals(status)&&!Boolean.TRUE.equals(row.get("sync_error")))continue;filtered.add(row);}
        int from=Math.min(filtered.size(),(page-1)*size),to=Math.min(filtered.size(),from+size);return m("rows",new ArrayList<>(filtered.subList(from,to)),"total",filtered.size(),"summary",m("shops",total,"unconfigured",unset,"incomplete",incomplete,"automatic",auto,"errors",error));
    }
    public Map<String,Object> detail(String c,Set<String> permissions,long channel){Map<String,Object> row=project(c,permissions,channel).stream().findFirst().orElseThrow(()->new IllegalArgumentException("店铺不存在或不属于当前公司"));if(can(permissions,"order:pull:config"))row.put("sources",sources.choices(c));return row;}
    private List<Map<String,Object>> project(String c,Set<String> p,Long channel){
        Map<String,Map<Long,Map<String,Object>>> configs=new LinkedHashMap<>();
        if(can(p,"order:pull:config")){
            configs.put("order",batch(c,"SELECT c.id,c.channel_id,c.source_key,c.seller_nick,c.enabled,c.auto_convert,c.allowed_trade_types,c.allow_public_stock,c.interval_seconds,c.initial_time,c.cursor_time,c.revision,c.last_error,s.source_name,s.enabled source_enabled FROM oms_order_pull_config c LEFT JOIN oms_order_push_source s ON s.company_code=c.company_code AND s.source_key=c.source_key WHERE c.company_code=?",channel));
            for(String[] item:new String[][]{{"review","oms_order_review_config"},{"allocation","oms_order_allocation_config"},{"carriers","oms_order_carrier_config"}})configs.put(item[0],rows(c,item[1],"c.channel_id,c.policy_json,c.revision",channel));
        }
        if(can(p,"order:shipment:platformConfig"))configs.put("shipping",rows(c,"oms_order_platform_shipping_config","c.channel_id,c.mode,c.control_version",channel));
        if(can(p,"order:refund:config")){
            configs.put("refund",rows(c,"oms_refund_config","c.channel_id,c.enabled,c.auto_convert,c.revision,c.last_success,c.last_error",channel));
            configs.put("refund_flow",rows(c,"oms_refund_flow_policy","c.channel_id,c.enabled,c.policy_json,c.revision",channel));
            configs.put("returns",rows(c,"oms_refund_parcel_config","c.channel_id,c.policy,c.revision",channel));
            configs.put("refund_api",rows(c,"oms_refund_api_cursor","c.channel_id,c.enabled,c.last_success,c.last_error",channel));
        }
        if(can(p,"order:exchange:config"))configs.put("exchange",rows(c,"oms_exchange_config","c.channel_id,c.enabled,c.auto_convert,c.write_enabled,c.initial_time,c.cursor_time,c.revision,c.last_error",channel));
        if(can(p,"order:exchange:config"))configs.put("exchange_flow",rows(c,"oms_exchange_flow_policy","c.channel_id,c.enabled,c.policy_json,c.revision",channel));
        Map<Long,Map<String,Object>> connected=can(p,"order:pull:config")?configs.get("order"):(can(p,"order:shipment:platformConfig")?rows(c,"oms_order_pull_config","c.channel_id",channel):Collections.emptyMap());
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> shop:catalog.channels(c)){
            long id=number(shop,"channel_id");if(!"TM".equals(text(shop,"channel_type"))||(channel!=null&&channel!=id))continue;
            Map<String,Object> row=m("channel_id",id,"channel_name",shop.get("channel_name"),"shop_enabled",shop.get("enabled"),"seller_nick",shop.get("seller_nick"));boolean configured=false,automatic=false,error=false;
            for(Map.Entry<String,Map<Long,Map<String,Object>>> entry:configs.entrySet()){
                Map<String,Object> stored=entry.getValue().get(id),value=stored==null?m():new LinkedHashMap<>(stored);if(stored!=null)configured=true;
                if(value.containsKey("policy_json")){Map<String,Object> policy=obj(value.remove("policy_json"));value.put("policy",policy);}
                if("order".equals(entry.getKey())&&value.containsKey("allowed_trade_types"))value.put("allowed_trade_types",tradeTypes(value.get("allowed_trade_types")));
                String kind=entry.getKey();Map<String,Object> policy=value.get("policy") instanceof Map?obj(value.get("policy")):m();
                if("returns".equals(kind)&&value.get("policy")!=null){policy=obj(value.get("policy"));value.put("policy",policy);}
                if("order".equals(kind)||"refund".equals(kind)||"refund_api".equals(kind)||"exchange".equals(kind)||"refund_flow".equals(kind)||"exchange_flow".equals(kind))automatic|=number(value,"enabled")==1;
                if("review".equals(kind))automatic|="AUTO".equals(text(policy,"mode"));
                if("allocation".equals(kind))automatic|=number(policy,"enabled")==1&&"AUTO".equals(text(policy,"execution_mode"));
                if("carriers".equals(kind))automatic|=number(policy,"enabled")==1;
                if("returns".equals(kind))automatic|=number(policy,"auto_create")==1||number(policy,"auto_dispatch")==1||number(policy,"auto_enabled")==1;
                if("shipping".equals(kind))automatic|="SIMULATION".equals(text(value,"mode"));
                error|=!text(value,"last_error").isEmpty();row.put(kind,value);
            }
            row.put("order_connected",connected.containsKey(id));row.put("configured",configured);row.put("automatic",number(shop,"enabled")==1&&automatic);row.put("sync_error",error);row.put("issues",issues(row));result.add(row);
        }result.sort(Comparator.comparingLong(row->number(row,"channel_id")));return result;
    }
    static Map<String,Object> part(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof Map?obj(value):m();}
    static List<Map<String,Object>> issues(Map<String,Object> row){List<Map<String,Object>> issues=new ArrayList<>();Map<String,Object> order=part(row,"order"),allocation=part(part(row,"allocation"),"policy");
        if(number(row,"shop_enabled")!=1){issues.add(m("section","overview","message","店铺已停用，自动流程暂停"));return issues;}
        if(number(order,"enabled")==1&&number(order,"source_enabled")!=1)issues.add(m("section","order-intake","message","订单同步开启，但推送来源未启用或已缺失"));
        if(number(allocation,"enabled")==1&&(text(allocation,"owner_code").isEmpty()||!(allocation.get("warehouse_order") instanceof List)||((List<?>)allocation.get("warehouse_order")).isEmpty()))issues.add(m("section","allocation","message","分仓已启用，货主或允许仓尚未完整配置"));
        for(String[] item:new String[][]{{"order","order-intake"},{"refund","refund-intake"},{"refund_api","refund-intake"},{"exchange","exchange"}}){String message=text(part(row,item[0]),"last_error");if(!message.isEmpty())issues.add(m("section",item[1],"message",message));}
        return issues;
    }
}
