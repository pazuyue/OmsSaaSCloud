package com.oms.inventory.service.impl.rule;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

@Service
public class DailyAllocationScanner {
    @Resource private JdbcTemplate db;
    @Resource private DailyAllocationService daily;
    private final Map<String,Long> cursors=new HashMap<>();
    public synchronized Map<String,Object> poll(String company) {
        if(company==null || !company.matches("[A-Za-z0-9_-]{1,50}"))throw new IllegalArgumentException("调度公司编码无效");
        company=company.toUpperCase(Locale.ROOT);long cursor=cursors.getOrDefault(company,0L);
        // Rotate through IDs so large rules cannot starve later rules. DB header locks prevent overlap.
        String sql="SELECT id,company_code FROM rule_stock_info WHERE company_code=? AND rule_type=1 AND daily_enabled=1 AND id>? AND (active_run_id IS NOT NULL OR next_run_at<=NOW() OR end_time<=NOW()) ORDER BY id LIMIT 10";
        List<Map<String,Object>> rules=db.query(sql,ROW,company,cursor);
        if(rules.isEmpty() && cursor>0){cursor=0;rules=db.query(sql,ROW,company,0);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("scanned",rules.size());List<Long> failures=new ArrayList<>();
        if(rules.isEmpty()){cursors.put(company,0L);return result;}
        long deadline=System.nanoTime()+20000000000L;
        for(Map<String,Object> row:rules) {
            if(System.nanoTime()>deadline)break;
            cursor=number(row,"id");
            try{daily.tick((String)row.get("companyCode"),cursor);}catch(RuntimeException e){failures.add(cursor);org.slf4j.LoggerFactory.getLogger(getClass()).error("Daily allocation deferred, rule={}",cursor,e);}
        }
        cursors.put(company,cursor);
        if(!failures.isEmpty())throw new IllegalStateException("日常分货扫描异常，待下次恢复的规则："+failures);
        return result;
    }
}
