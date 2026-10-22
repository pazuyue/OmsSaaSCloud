package com.oms.order.service;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.oms.order.service.GiftRules.*;
import static com.oms.order.service.OrderValues.*;

/** Same database/transaction as the frozen gift plan. No remote calls or process-local quota cache. */
final class GiftQuota {
    private final JdbcTemplate db;
    GiftQuota(JdbcTemplate db){this.db=db;}
    static final class ConfigurationChanged extends RuntimeException {}

    /** Shared revision locks fence cached unlimited rules against a newly configured limit. */
    void fence(String company,List<Map<String,Object>> groups){
        Map<Long,Long> expected=new TreeMap<>();for(Map<String,Object> g:groups)expected.put(number(g,"id"),number(g,"revision"));
        if(expected.isEmpty())return;
        List<Object> args=new ArrayList<>();args.add(company);args.addAll(expected.keySet());
        List<Map<String,Object>> actual=db.query("SELECT id,revision,enabled FROM oms_gift_group WHERE company_code=? AND id IN ("+marks(expected.size())+") ORDER BY id LOCK IN SHARE MODE",ROW,args.toArray());
        if(actual.size()!=expected.size())throw new ConfigurationChanged();
        for(Map<String,Object> g:actual)if(number(g,"enabled")!=1||!Objects.equals(expected.get(number(g,"id")),number(g,"revision")))throw new ConfigurationChanged();
    }

    /** Called under the strategy group's exclusive save lock. Preserve counters even if rules are removed. */
    void configure(String company,long group,Map<String,Object> policy){
        Map<String,Map<String,Object>> old=new HashMap<>();
        for(Map<String,Object> row:db.query("SELECT * FROM oms_gift_quota WHERE company_code=? AND group_id=? ORDER BY rule_key FOR UPDATE",ROW,company,group))old.put(text(row,"rule_key"),row);
        List<Map<String,Object>> rules=rows(policy.get("rules"));
        for(Map<String,Object> rule:rules){String key=text(rule,"key");long maximum=number(rule,"max_count");Map<String,Object> row=old.get(key);
            boolean tracked=row!=null||maximum>0;rule.put("quota_tracked",tracked);
            if(!tracked)continue;
            if(row==null)db.update("INSERT INTO oms_gift_quota(company_code,group_id,rule_key,max_count) VALUES (?,?,?,?)",company,group,key,maximum);
            else {require(maximum==0||maximum>=number(row,"used_count"),"规则「"+text(rule,"name")+"」的上限不能低于已占用次数（"+number(row,"used_count")+"次）");db.update("UPDATE oms_gift_quota SET max_count=? WHERE company_code=? AND group_id=? AND rule_key=?",maximum,company,group,key);}
        }
        policy.put("rules",rules);
    }

    Map<Long,Map<String,Object>> usages(String company,Collection<Long> groups){
        Map<Long,Map<String,Object>> result=new HashMap<>();if(groups.isEmpty())return result;
        List<Object> args=new ArrayList<>();args.add(company);args.addAll(groups);
        for(Map<String,Object> row:db.query("SELECT group_id,rule_key,max_count,used_count FROM oms_gift_quota WHERE company_code=? AND group_id IN ("+marks(groups.size())+")",ROW,args.toArray())){
            long max=number(row,"max_count"),used=number(row,"used_count");result.computeIfAbsent(number(row,"group_id"),id->new LinkedHashMap<>()).put(text(row,"rule_key"),map("max_count",max,"used_count",used,"remaining_count",max==0?null:Math.max(0,max-used)));
        }return result;
    }
    void decorate(String company,List<Map<String,Object>> groups){
        List<Long> ids=new ArrayList<>();for(Map<String,Object> group:groups)ids.add(number(group,"id"));Map<Long,Map<String,Object>> usage=usages(company,ids);
        for(Map<String,Object> group:groups)group.put("quota_usage",usage.getOrDefault(number(group,"id"),Collections.emptyMap()));
    }
    static GrantAllocator preview(Map<Long,Map<String,Object>> usage){
        return (group,rule,requested)->{
            long max=number(rule,"max_count");if(max==0)return requested;
            Object raw=usage.getOrDefault(number(group,"id"),Collections.emptyMap()).get(text(rule,"key"));
            long used=raw==null?0:number(object(raw),"used_count");return Math.min(requested,Math.max(0,max-used));
        };
    }

    Reservation lock(String company,List<Map<String,Object>> groups,Map<String,Map<String,Object>> calculated){
        // Sort by stable primary-key order, independent of shop-specific strategy execution order.
        Map<Long,SortedSet<String>> candidates=new TreeMap<>();
        for(Map<String,Object> group:groups)for(Map<String,Object> rule:rows(object(group.get("policy")).get("rules"))){
            Map<String,Object> e=calculated.get(identity(number(group,"id"),text(rule,"key")));
            if(e!=null&&Boolean.TRUE.equals(e.get("matched"))&&(number(rule,"max_count")>0||Boolean.TRUE.equals(rule.get("quota_tracked"))))candidates.computeIfAbsent(number(group,"id"),id->new TreeSet<>()).add(text(rule,"key"));
        }
        Map<String,Map<String,Object>> locked=new HashMap<>();
        if(candidates.isEmpty())return new Reservation(company,locked);
        // Counters never decrease. Under the shared configuration fence, observed exhaustion is final.
        // Read exhausted quotas without exclusive row locks, avoiding a hot lock after sell-out.
        List<Object> readArgs=new ArrayList<>();readArgs.add(company);readArgs.addAll(candidates.keySet());
        Map<String,Map<String,Object>> observed=new HashMap<>();
        for(Map<String,Object> row:db.query("SELECT group_id,rule_key,max_count,used_count FROM oms_gift_quota WHERE company_code=? AND group_id IN ("+marks(candidates.size())+")",ROW,readArgs.toArray()))observed.put(identity(number(row,"group_id"),text(row,"rule_key")),row);
        for(Map.Entry<Long,SortedSet<String>> entry:candidates.entrySet()){
            List<String> available=new ArrayList<>();
            for(String key:entry.getValue()){
                String identity=identity(entry.getKey(),key);Map<String,Object> row=observed.get(identity);require(row!=null,"赠品累计额度配置缺失，请重新保存策略");
                if(number(row,"max_count")>0&&number(row,"used_count")>=number(row,"max_count"))locked.put(identity,row);else available.add(key);
            }
            if(available.isEmpty())continue;
            List<Object> args=new ArrayList<>();args.add(company);args.add(entry.getKey());args.addAll(available);
            List<Map<String,Object>> rows=db.query("SELECT group_id,rule_key,max_count,used_count FROM oms_gift_quota WHERE company_code=? AND group_id=? AND rule_key IN ("+marks(available.size())+") ORDER BY rule_key FOR UPDATE",ROW,args.toArray());
            require(rows.size()==available.size(),"赠品累计额度配置缺失，请重新保存策略");
            for(Map<String,Object> row:rows)locked.put(identity(number(row,"group_id"),text(row,"rule_key")),row);
        }
        return new Reservation(company,locked);
    }

    final class Reservation implements GrantAllocator {
        private final String company;
        private final Map<String,Map<String,Object>> locked;
        private final Map<String,Long> grants=new TreeMap<>();
        private final Map<String,Long> requests=new HashMap<>();
        Reservation(String company,Map<String,Map<String,Object>> locked){this.company=company;this.locked=locked;}
        @Override public long grant(Map<String,Object> group,Map<String,Object> rule,long requested){
            String key=identity(number(group,"id"),text(rule,"key"));Map<String,Object> row=locked.get(key);
            if(row==null){require(number(rule,"max_count")==0&&!Boolean.TRUE.equals(rule.get("quota_tracked")),"赠品额度未锁定");return requested;}
            long max=number(row,"max_count"),used=number(row,"used_count"),grant=max==0?requested:Math.min(requested,Math.max(0,max-used));
            require(!requests.containsKey(key),"同一赠品额度不能在订单中重复执行");requests.put(key,requested);if(grant>0)grants.put(key,grant);return grant;
        }
        void persist(long platform,long plan){
            if(grants.isEmpty())return;
            List<Object[]> counters=new ArrayList<>(),ledger=new ArrayList<>();
            for(Map.Entry<String,Long> grant:grants.entrySet()){
                Map<String,Object> row=locked.get(grant.getKey());long count=grant.getValue();
                counters.add(new Object[]{count,company,row.get("group_id"),row.get("rule_key"),count});
                ledger.add(new Object[]{company,platform,plan,row.get("group_id"),row.get("rule_key"),requests.get(grant.getKey()),count,row.get("max_count")});
            }
            for(int changed:db.batchUpdate("UPDATE oms_gift_quota SET used_count=used_count+? WHERE company_code=? AND group_id=? AND rule_key=? AND (max_count=0 OR used_count+?<=max_count)",counters))require(changed==1||changed==java.sql.Statement.SUCCESS_NO_INFO,"赠品额度扣减失败，请重试");
            db.batchUpdate("INSERT INTO oms_gift_quota_award(company_code,platform_order_id,plan_id,group_id,rule_key,requested_count,granted_count,max_count) VALUES (?,?,?,?,?,?,?,?)",ledger);
        }
    }
    static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
}
