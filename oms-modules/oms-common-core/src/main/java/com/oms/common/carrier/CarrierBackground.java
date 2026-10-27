package com.oms.common.carrier;

import com.oms.common.tenant.TenantRouting;
import org.springframework.stereotype.Component;
import javax.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Carrier integrations cannot occupy the order scheduler or leak a tenant binding between worker threads. */
@Component
@org.springframework.scheduling.annotation.EnableScheduling
public class CarrierBackground {
    @Resource private TenantRouting tenants;
    private final Set<String> pending=ConcurrentHashMap.newKeySet();
    private final AtomicInteger cursor=new AtomicInteger(),sequence=new AtomicInteger();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),work->{Thread t=new Thread(work,"carrier-background-"+sequence.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public void each(String kind,Consumer<String> work){List<String> scopes=new ArrayList<>(tenants.continuingCompanies());if(scopes.isEmpty())return;int first=Math.floorMod(cursor.getAndAdd(2),scopes.size());for(int n=0;n<Math.min(2,scopes.size());n++){String company=scopes.get((first+n)%scopes.size()),key=kind+":"+company;if(!pending.add(key))continue;try{TenantRouting.Route route=tenants.captureForContinuation(company);executor.execute(()->{try{route.run(()->work.accept(company));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Carrier background work unavailable company={} kind={}",company,kind);}finally{pending.remove(key);}});}catch(RuntimeException rejected){pending.remove(key);}}}
    @PreDestroy public void close(){executor.shutdownNow();}
}
