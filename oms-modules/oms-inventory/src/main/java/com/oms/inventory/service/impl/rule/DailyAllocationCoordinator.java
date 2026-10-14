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
    @Resource private com.oms.common.tenant.TenantRouting tenantRouting;
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
            for(String company:tenantRouting.companies()) {
                if(closing)break;
                try {
                    com.oms.common.tenant.TenantRouting.Route route=tenantRouting.capture(company);
                    String scope=route.datasource()+"/"+company;
                    if(!active.add(scope))continue;
                    try{workers.execute(()->{
                        com.oms.common.tenant.TenantRouting.clearContext();
                        try{route.run(()->scanner.drain(company));}
                        catch(RuntimeException e){failed(scope,e);}
                        finally{com.oms.common.tenant.TenantRouting.clearContext();active.remove(scope);}
                    });}catch(RuntimeException e){active.remove(scope);throw e;}
                }catch(RuntimeException e){failed(company,e);}
            }
        }catch(RuntimeException e){failed("全局扫描",e);}
        finally{discovering.set(false);}
    }
    private void failed(String scope,RuntimeException e){failures.add(scope);org.slf4j.LoggerFactory.getLogger(getClass()).error("Daily allocation background task failed, scope={}",scope,e);}
    @PreDestroy public void stop(){closing=true;if(dispatcher!=null)dispatcher.shutdownNow();if(workers!=null)workers.shutdownNow();}
}
