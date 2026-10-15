package com.oms.order.service;
import com.oms.common.task.ScanReport;
import com.oms.common.task.ScopedWorkExecutor;
import com.oms.common.tenant.TenantRouting;
import org.springframework.stereotype.Component;
import javax.annotation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.oms.order.service.OrderValues.*;
@Component
public class OrderAutoAllocationWorker {
    @Resource private TenantRouting tenantRouting;
    @Resource private OrderAutoAllocationService service;
    @Resource private OrderCoordinator coordinator;
    private final ScopedWorkExecutor executor=new ScopedWorkExecutor("auto-allocation");
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger cursor=new java.util.concurrent.atomic.AtomicInteger();
    public void kick(String company){if(!active.add(company))return;boolean accepted=false;try{accepted=executor.submit(tenantRouting.capture(company),()->{try{runCompany(company);}finally{active.remove(company);}});}finally{if(!accepted)active.remove(company);}}
    private Map<String,Object> runCompany(String company){return runCompany(company,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60));}
    private Map<String,Object> runCompany(String company,long deadline){ScanReport report=new ScanReport();for(Map<String,Object> task:service.due(company)){if(System.nanoTime()>=deadline)break;long id=number(task,"order_id");try{service.process(company,id);Map<String,Object> result=service.taskState(company,id);String state=text(result,"state");report.record(company,id,text(task,"order_sn"),"DONE".equals(state)?"SUCCESS":"NEEDS_ACTION".equals(state)?"FAILED":"UNCERTAIN".equals(state)?"UNKNOWN":state,text(result,"reason"));}catch(RuntimeException e){report.record(company,id,text(task,"order_sn"),"FAILED","待办已保留，等待下次扫描");}}return report;}
    public Map<String,Object> poll(){
        ScanReport report=new ScanReport();List<String> companies=new ArrayList<>(tenantRouting.companies());if(companies.isEmpty())return report;
        // Start with a different company next time. Remote timeouts in one tenant must not starve the rest.
        int start=Math.floorMod(cursor.getAndIncrement(),companies.size());long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        for(int offset=0;offset<companies.size()&&System.nanoTime()<deadline;offset++){
            String company=companies.get((start+offset)%companies.size());
            try(TenantRouting.Binding ignored=tenantRouting.open(company)){
                try{if(service.wakeStock(company)>0||service.reviewStockDue(company))coordinator.poll();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Stock wake unavailable company={}",company);}
                if(System.nanoTime()<deadline)report.merge(runCompany(company,deadline));
            }catch(RuntimeException e){report.scopeFailure(company);}
        }return report;
    }
    @PreDestroy public void close(){executor.close();}
}
