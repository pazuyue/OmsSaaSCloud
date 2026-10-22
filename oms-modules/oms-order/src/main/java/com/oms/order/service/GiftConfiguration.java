package com.oms.order.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.GiftRules.*;

/** Shop membership and execution order belong to the association, not the reusable strategy. */
@Service public class GiftConfiguration {
    @Resource OrderRepository repository;
    @Resource OrderCatalog catalog;

    public List<Map<String,Object>> scopes(String c){
        List<Map<String,Object>> list=repository.db().query("SELECT * FROM oms_gift_shop_group WHERE company_code=? ORDER BY id",ROW,c);
        for(Map<String,Object> row:list){Map<String,Object> config=object(row.remove("config_json"));row.putAll(config);}return list;
    }
    public Map<String,Object> save(String c,Map<String,Object> input,String actor){
        String name=required(input,"name",100);long id=number(input,"id"),enabled=number(input,"enabled");require(enabled==0||enabled==1,"店铺组状态无效");
        require(input.get("channels") instanceof List,"请选择店铺");Set<Long> channels=new LinkedHashSet<>();
        for(Object v:(List<?>)input.get("channels")){long channel=Long.parseLong(String.valueOf(v));require(channel>0&&channels.add(channel),"店铺重复或无效");}
        require(!channels.isEmpty()&&channels.size()<=100,"每个店铺组请选择1至100家店铺");
        Set<Long> valid=new HashSet<>();for(Map<String,Object> shop:catalog.channels(c))if(number(shop,"enabled")==1)valid.add(number(shop,"channel_id"));require(valid.containsAll(channels),"店铺不存在、已停用或不属于当前公司");
        List<Map<String,Object>> links=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(Map<String,Object> link:rows(input.get("links"))){long group=number(link,"group_id");require(group>0&&seen.add(group),"同一策略组不能重复关联");links.add(map("group_id",group,"continue_after_match",Boolean.TRUE.equals(link.get("continue_after_match"))));}
        require(links.size()<=50,"每个店铺组最多关联50个策略组");
        Map<String,Object> config=map("channels",new ArrayList<>(channels),"links",links);
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));
        return tx.execute(s->{
            // Serialize membership changes per company; the unique member key also enforces the invariant.
            repository.db().update("INSERT IGNORE INTO oms_gift_config_lock(company_code) VALUES (?)",c);
            repository.db().queryForObject("SELECT company_code FROM oms_gift_config_lock WHERE company_code=? FOR UPDATE",String.class,c);
            require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_gift_shop_group WHERE company_code=? AND name=? AND id<>?",Long.class,c,name,id)==0,"店铺组名称已存在");
            Set<Long> strategies=new HashSet<>(repository.db().queryForList("SELECT id FROM oms_gift_group WHERE company_code=?",Long.class,c));require(strategies.containsAll(seen),"关联策略组不存在或不属于当前公司");
            long current=id;if(id==0){require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_gift_shop_group WHERE company_code=?",Long.class,c)<100,"每公司最多100个赠品店铺组");current=repository.insert("INSERT INTO oms_gift_shop_group(company_code,name,enabled,config_json,updated_by) VALUES (?,?,?,?,?)",c,name,enabled,encode(config),actor);}
            else {List<Map<String,Object>> old=repository.db().query("SELECT revision FROM oms_gift_shop_group WHERE company_code=? AND id=? FOR UPDATE",ROW,c,id);require(old.size()==1&&number(old.get(0),"revision")==number(input,"revision"),"店铺组不存在或配置已变化，请刷新后重试");repository.db().update("UPDATE oms_gift_shop_group SET name=?,enabled=?,config_json=?,updated_by=?,revision=revision+1 WHERE company_code=? AND id=?",name,enabled,encode(config),actor,c,id);}
            repository.db().update("DELETE FROM oms_gift_shop_member WHERE company_code=? AND shop_group_id=?",c,current);
            if(enabled==1)for(Long channel:channels){List<String> conflicts=repository.db().queryForList("SELECT g.name FROM oms_gift_shop_member m JOIN oms_gift_shop_group g ON g.company_code=m.company_code AND g.id=m.shop_group_id WHERE m.company_code=? AND m.channel_id=?",String.class,c,channel);require(conflicts.isEmpty(),"店铺 "+channel+" 已属于启用的店铺组："+String.join("、",conflicts));repository.db().update("INSERT INTO oms_gift_shop_member(company_code,channel_id,shop_group_id) VALUES (?,?,?)",c,channel,current);}
            Map<String,Object> saved=repository.db().query("SELECT * FROM oms_gift_shop_group WHERE company_code=? AND id=?",ROW,c,current).get(0);
            repository.db().update("INSERT INTO oms_gift_shop_group_version(company_code,shop_group_id,revision,snapshot,actor) VALUES (?,?,?,?,?)",c,current,saved.get("revision"),encode(saved),actor);return map("id",current);
        });
    }
    List<Map<String,Object>> compile(String c,List<Map<String,Object>> groups){return compile(scopes(c),groups);}
    static List<Map<String,Object>> compile(List<Map<String,Object>> scopes,List<Map<String,Object>> groups){
        Map<Long,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> g:groups)byId.put(number(g,"id"),g);
        List<Map<String,Object>> compiled=new ArrayList<>();
        for(Map<String,Object> scope:scopes){if(number(scope,"enabled")!=1)continue;int sequence=0;
            for(Map<String,Object> link:rows(scope.get("links"))){sequence++;Map<String,Object> original=byId.get(number(link,"group_id"));require(original!=null,"店铺组关联的赠品策略组不存在，请核对配置");if(number(original,"enabled")!=1)continue;
                Map<String,Object> group=new LinkedHashMap<>(original),p=object(original.get("policy"));p.put("channels",scope.get("channels"));p.put("cross_group_stack",Boolean.TRUE.equals(link.get("continue_after_match")));group.put("policy",p);group.put("priority",sequence);group.put("shop_group_id",scope.get("id"));group.put("shop_group_name",scope.get("name"));group.put("shop_group_revision",scope.get("revision"));compiled.add(group);
            }
        }return compiled;
    }
    public Map<String,Object> effective(String c,long channel,List<Map<String,Object>> groups){
        require(catalog.channels(c).stream().anyMatch(v->number(v,"channel_id")==channel),"店铺不存在或无权访问");
        Map<Long,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> g:groups)byId.put(number(g,"id"),g);
        List<Map<String,Object>> matches=new ArrayList<>();
        for(Map<String,Object> scope:scopes(c))if(((List<?>)scope.get("channels")).stream().anyMatch(v->String.valueOf(v).equals(String.valueOf(channel)))){
            List<Map<String,Object>> executions=new ArrayList<>();int sequence=0;for(Map<String,Object> link:rows(scope.get("links"))){Map<String,Object> g=byId.get(number(link,"group_id"));if(g==null)continue;executions.add(map("sequence",++sequence,"group_id",g.get("id"),"name",g.get("name"),"enabled",g.get("enabled"),"revision",g.get("revision"),"policy",g.get("policy"),"continue_after_match",link.get("continue_after_match")));}scope.put("executions",executions);matches.add(scope);
        }return map("channel_id",channel,"shop_groups",matches);
    }
}
