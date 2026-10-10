package com.oms.inventory.service.impl.rule;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import java.util.*;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

@Service
public class DailyAllocationScanner {
    @Resource private JdbcTemplate db;
    @Resource private DailyAllocationService daily;
    private final Map<String,Long> cursors=new HashMap<>();
    /** All due rules join this sweep. Keep rotating batches until their current rounds finish. */
    public void drain(String company) {
        if(company==null || !company.matches("[A-Za-z0-9_-]{1,50}"))throw new IllegalArgumentException("调度公司编码无效");
        company=company.toUpperCase(Locale.ROOT);
        java.sql.Timestamp cutoff=db.queryForObject("SELECT NOW()",java.sql.Timestamp.class);
        long cursor=0;Deque<Long> pending=new ArrayDeque<>();List<Long> failures=new ArrayList<>();
        while(!Thread.currentThread().isInterrupted()) {
            List<Long> ids=db.queryForList("SELECT id FROM rule_stock_info WHERE company_code=? AND rule_type=1 AND daily_enabled=1 AND id>? AND (active_run_id IS NOT NULL OR next_run_at<=? OR end_time<=?) ORDER BY id LIMIT 100",Long.class,company,cursor,cutoff,cutoff);
            if(ids.isEmpty())break;
            pending.addAll(ids);cursor=ids.get(ids.size()-1);
        }
        while(!pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
            long id=pending.removeFirst();
            try{if(daily.tick(company,id))pending.addLast(id);}
            catch(RuntimeException e){failures.add(id);org.slf4j.LoggerFactory.getLogger(getClass()).error("Daily allocation deferred, rule={}",id,e);}
        }
        if(!failures.isEmpty())throw new IllegalStateException("日常分货扫描异常，待下次恢复的规则："+failures);
    }
    public synchronized Map<String,Object> poll(String company) {
        return poll(company,System.nanoTime()+20000000000L,10);
    }
    synchronized Map<String,Object> poll(String company,long deadline,int maxRules) {
        if(company==null || !company.matches("[A-Za-z0-9_-]{1,50}"))throw new IllegalArgumentException("调度公司编码无效");
        company=company.toUpperCase(Locale.ROOT);
        String scope=String.valueOf(DynamicDataSourceContextHolder.peek())+"/"+company;
        long cursor=cursors.getOrDefault(scope,0L);
        // Rotate through IDs so large rules cannot starve later rules. DB header locks prevent overlap.
        String sql="SELECT id,company_code FROM rule_stock_info WHERE company_code=? AND rule_type=1 AND daily_enabled=1 AND id>? AND (active_run_id IS NOT NULL OR next_run_at<=NOW() OR end_time<=NOW()) ORDER BY id LIMIT ?";
        List<Map<String,Object>> rules=db.query(sql,ROW,company,cursor,maxRules);
        if(rules.isEmpty() && cursor>0){cursor=0;rules=db.query(sql,ROW,company,0,maxRules);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("scanned",rules.size());List<Long> failures=new ArrayList<>();
        if(rules.isEmpty()){cursors.put(scope,0L);return result;}
        for(Map<String,Object> row:rules) {
            if(System.nanoTime()>deadline)break;
            cursor=number(row,"id");
            try{daily.tick((String)row.get("companyCode"),cursor);}catch(RuntimeException e){failures.add(cursor);org.slf4j.LoggerFactory.getLogger(getClass()).error("Daily allocation deferred, rule={}",cursor,e);}
        }
        cursors.put(scope,cursor);
        if(!failures.isEmpty())throw new IllegalStateException("日常分货扫描异常，待下次恢复的规则："+failures);
        return result;
    }
}
