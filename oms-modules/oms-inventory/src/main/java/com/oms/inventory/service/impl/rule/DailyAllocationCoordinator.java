package com.oms.inventory.service.impl.rule;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Quartz discovers work; one queued/running task per datasource/company finishes its rounds.
 * Queue entries are companies, never individual SKUs. No transaction spans datasource switches.
 */
@Service
public class DailyAllocationCoordinator {
    @Resource private DataSource dataSource;
    @Resource private DailyAllocationScanner scanner;
    @Value("${inventory.daily.company-concurrency:2}") private int concurrency=2;
    private final AtomicBoolean discovering=new AtomicBoolean();
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private final Queue<String> failures=new ConcurrentLinkedQueue<>();
    private volatile boolean closing;
    private ExecutorService dispatcher,workers;

    @PostConstruct public void start() {
        if(concurrency<1 || concurrency>16)throw new IllegalArgumentException("日常分货公司并行度须为1至16");
        dispatcher=Executors.newSingleThreadExecutor(r->new Thread(r,"daily-dispatch"));
        workers=Executors.newFixedThreadPool(concurrency,r->new Thread(r,"daily-company"));
    }
    public Map<String,Object> poll() {
        if(closing)throw new IllegalStateException("库存服务正在停止");
        List<String> reported=new ArrayList<>();String failure;
        while((failure=failures.poll())!=null)reported.add(failure);
        boolean accepted=discovering.compareAndSet(false,true);
        if(accepted)try{dispatcher.execute(this::discover);}catch(RuntimeException e){discovering.set(false);throw e;}
        Map<String,Object> result=new LinkedHashMap<>();result.put("accepted",accepted);result.put("activeCompanies",active.size());result.put("failures",reported);return result;
    }
    private void discover() {
        try {
            if(!(dataSource instanceof DynamicRoutingDataSource))throw new IllegalStateException("库存动态数据源未配置");
            DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;
            SortedMap<String,DataSource> pools=new TreeMap<>(routing.getDataSources());
            if(pools.isEmpty())throw new IllegalStateException("没有已加载的库存数据源");
            for(Map.Entry<String,DataSource> entry:pools.entrySet()) {
                if(closing)break;
                String route=entry.getKey(),cursor="";
                try {
                    exactRoute(routing,route,entry.getValue());
                    JdbcTemplate discovery=new JdbcTemplate(entry.getValue());discovery.setQueryTimeout(5);
                    while(!closing) {
                        // Page size limits read memory only; every company page is visited.
                        List<String> companies=discovery.queryForList("SELECT DISTINCT company_code FROM rule_stock_info FORCE INDEX (idx_daily_company_scan) WHERE rule_type=1 AND daily_enabled=1 AND company_code>? ORDER BY company_code LIMIT 100",String.class,cursor);
                        for(String company:companies) {
                            if(closing)break;
                            cursor=company;String scope=route+"/"+company.toUpperCase(Locale.ROOT);
                            if(!active.add(scope))continue;
                            try {
                                workers.execute(()->{
                                    try{exactRoute(routing,route,entry.getValue());DynamicDataSourceContextHolder.push(route);scanner.drain(company);}
                                    catch(RuntimeException e){failed(scope,e);}
                                    finally{DynamicDataSourceContextHolder.clear();active.remove(scope);}
                                });
                            } catch(RuntimeException e){active.remove(scope);throw e;}
                        }
                        if(companies.size()<100)break;
                    }
                } catch(RuntimeException e){failed(route+"/数据源",e);}
            }
        } catch(RuntimeException e){failed("全局扫描",e);}
        finally{discovering.set(false);}
    }
    private void exactRoute(DynamicRoutingDataSource routing,String route,DataSource pool) {
        if(!routing.getDataSources().containsKey(route) || routing.getDataSource(route)!=pool)throw new IllegalStateException("数据源已移除或路由存在歧义");
    }
    private void failed(String scope,RuntimeException e){failures.add(scope);org.slf4j.LoggerFactory.getLogger(getClass()).error("Daily allocation background task failed, scope={}",scope,e);}
    @PreDestroy public void stop(){closing=true;if(dispatcher!=null)dispatcher.shutdownNow();if(workers!=null)workers.shutdownNow();}
}
