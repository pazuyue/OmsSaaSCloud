package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.map;

/** Local projections only. Catalog performs one company-scoped channel read, never a platform probe per shop. */
@Service public class OrderIntakeWorkspace {
    @Resource OrderRepository repository;
    @Resource OrderCatalog catalog;
    @Resource ConfiguredPushReader sources;
    public Map<String,Object> overview(String company,Set<String> configTypes){
        Map<String,Map<Long,Map<String,Object>>> configs=new HashMap<>();
        for(String type:Arrays.asList("ORDER","REFUND","EXCHANGE")){Map<Long,Map<String,Object>> byShop=new HashMap<>();if(configTypes.contains(type)){
            String table=type.equals("ORDER")?"oms_order_pull_config":type.equals("REFUND")?"oms_refund_config":"oms_exchange_config";
            String fields=type.equals("ORDER")?"c.id,c.channel_id,c.source_key,c.seller_nick,c.enabled,c.auto_convert,c.allowed_trade_types,c.allow_public_stock,c.interval_seconds,c.initial_time,c.cursor_time,c.revision,c.last_error,s.source_name,s.enabled source_enabled":type.equals("REFUND")?"c.channel_id,c.source_key,c.seller_nick,c.enabled,c.auto_convert,c.interval_seconds,c.initial_time,c.cursor_time,c.revision,c.last_error,c.last_success,s.source_name,s.enabled source_enabled":"c.channel_id,c.enabled,c.write_enabled,c.auto_convert,c.initial_time,c.cursor_time,c.revision,c.last_error";
            String join=type.equals("EXCHANGE")?"":" LEFT JOIN oms_order_push_source s ON s.company_code=c.company_code AND s.source_key=c.source_key";
            for(Map<String,Object> c:repository.db().query("SELECT "+fields+" FROM "+table+" c"+join+" WHERE c.company_code=?",ROW,company)){if(type.equals("ORDER"))c.put("allowed_trade_types",tradeTypes(c.get("allowed_trade_types")));byShop.put(number(c,"channel_id"),c);}}
            configs.put(type,byShop);
        }
        List<Map<String,Object>> rows=new ArrayList<>();for(Map<String,Object> shop:catalog.channels(company))if("TM".equals(text(shop,"channel_type"))){Map<String,Object> row=map("channel_id",shop.get("channel_id"),"channel_name",shop.get("channel_name"),"channel_type",shop.get("channel_type"),"enabled",shop.get("enabled"),"seller_nick",shop.get("seller_nick"));for(String type:configTypes){Map<String,Object> c=configs.get(type).get(number(shop,"channel_id"));row.put(type.toLowerCase(Locale.ROOT),c);}rows.add(row);}
        return map("rows",rows,"sources",configTypes.isEmpty()?Collections.emptyList():sources.choices(company));
    }
    public Map<String,Object> tasks(String company,Set<String> allowed,Map<String,String> input){
        String kind=input.getOrDefault("kind","");require(kind.isEmpty()||Arrays.asList("ORDER","REFUND","EXCHANGE").contains(kind),"同步类型无效");require(kind.isEmpty()||allowed.contains(kind),"无权查看该类型同步任务");Set<String> types=new LinkedHashSet<>(allowed);if(!kind.isEmpty())types.retainAll(Collections.singleton(kind));if(types.isEmpty())return map("rows",Collections.emptyList(),"total",0);
        String state=input.getOrDefault("state","");require(state.isEmpty()||Arrays.asList("PENDING","RUNNING","COMPLETED","RETRY","FAILED","PARTIAL").contains(state),"任务状态无效");String channel=input.getOrDefault("channelId",""),id=input.getOrDefault("taskId","");require(channel.isEmpty()||channel.matches("[1-9][0-9]{0,18}"),"店铺无效");require(id.isEmpty()||id.matches("[1-9][0-9]{0,18}"),"任务编号无效");int count=20,page=1;try{count=Math.min(50,size(Integer.parseInt(input.getOrDefault("pageSize","20"))));page=Integer.parseInt(input.getOrDefault("pageNum","1"));}catch(Exception e){throw new IllegalArgumentException("分页参数无效");}require(page>=1&&page<=1000,"请缩小筛选范围，每次最多浏览1000页");int offset=offset(page,count);long total=0;List<String> branches=new ArrayList<>();List<Object> args=new ArrayList<>();
        for(String type:Arrays.asList("ORDER","REFUND","EXCHANGE")){if(!types.contains(type))continue;boolean order=type.equals("ORDER");String table=order?"oms_order_pull_task":type.equals("REFUND")?"oms_refund_sync":"oms_exchange_sync",status=order?"IF(p.status='SUCCESS','COMPLETED',p.status)":"p.state";String where=" WHERE p.company_code=?";List<Object> own=new ArrayList<>(Collections.singleton(company));if(!channel.isEmpty()){where+=" AND p.channel_id=?";own.add(Long.parseLong(channel));}if(!id.isEmpty()){where+=" AND p.id=?";own.add(Long.parseLong(id));}if(!state.isEmpty()){where+=" AND p."+(order?"status":"state")+"=?";own.add(order&&state.equals("COMPLETED")?"SUCCESS":state);}total+=repository.db().queryForObject("SELECT COUNT(*) FROM "+table+" p"+where,Long.class,own.toArray());
            String fields="'"+type+"' kind,p.id,p.channel_id,"+(order?"IF(p.task_type='AUTO','AUTO',IF(p.pull_mode='TID','IDS','MANUAL'))":"p.mode")+" mode,"+status+" state,"+(order?"p.status":"p.state")+" original_state,p.start_time,p.end_time,p.create_time,p.attempts,"+(order?"p.pulled_count":"p.synced_count")+" read_count,p.new_count,p.updated_count,p.unchanged_count,p.failed_count,"+(order?"p.error_message":"p.last_error")+" last_error,"+(order?"p.operator_name":"p.actor")+" actor";
            branches.add("(SELECT "+fields+" FROM "+table+" p"+where+" ORDER BY p.create_time DESC,p.id DESC LIMIT ?)");args.addAll(own);args.add(offset+count);
        }
        args.add(count);args.add(offset);List<Map<String,Object>> rows=repository.db().query("SELECT * FROM ("+String.join(" UNION ALL ",branches)+") t ORDER BY create_time DESC,kind,id DESC LIMIT ? OFFSET ?",ROW,args.toArray());for(Map<String,Object> row:rows)row.put("task_key",text(row,"kind")+":"+row.get("id"));repository.messageProgress(company,rows.stream().filter(r->"ORDER".equals(text(r,"kind"))).collect(java.util.stream.Collectors.toList()),"ORDER_PULL");repository.messageProgress(company,rows.stream().filter(r->"REFUND".equals(text(r,"kind"))).collect(java.util.stream.Collectors.toList()),"REFUND_PULL");repository.messageProgress(company,rows.stream().filter(r->"EXCHANGE".equals(text(r,"kind"))).collect(java.util.stream.Collectors.toList()),"EXCHANGE_PULL");return map("rows",rows,"total",total);
    }
}
