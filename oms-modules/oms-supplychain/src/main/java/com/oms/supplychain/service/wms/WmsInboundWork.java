package com.oms.supplychain.service.wms;

import com.oms.common.task.ScopedWorkExecutor;
import com.oms.common.task.CommittedWork;
import com.oms.common.tenant.TenantRouting;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import javax.annotation.*;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Bounded immediate wakeup; original committed records remain eligible for the existing inbound scan. */
@Component
public class WmsInboundWork {
    @Resource private TenantRouting tenantRouting;
    @Resource @Lazy private PurchaseCutoffService cutoff;
    @Resource @Lazy private WmsInboundService inbound;
    @Resource private WmsStore db;
    private final ScopedWorkExecutor work=new ScopedWorkExecutor("purchase-immediate");
    public void dispatchReady(String company,long task){CommittedWork.afterCommit(()->{if(!work.submit(tenantRouting.captureForContinuation(company),()->inbound.dispatch(company,task)))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Purchase dispatch wakeup deferred company={} task={}",company,task);});}
    public void cutoffReady(String company,long op){CommittedWork.afterCommit(()->{if(!work.submit(tenantRouting.captureForContinuation(company),()->cutoff.process(company,op)))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Purchase cutoff wakeup deferred company={} op={}",company,op);});}
    public void receiptReady(String company,long ticket){CommittedWork.afterCommit(()->{if(!work.submit(tenantRouting.captureForContinuation(company),()->{
        Map<String,Object> task=inbound.task(company,ticket);if(task!=null&&Arrays.asList("PARTIAL","COMPLETE").contains(text(task.get("receipt_state"))))inbound.post(company,ticket);
        for(Long op:db.jdbc.queryForList("SELECT n.cutoff_id FROM no_tickets n JOIN wms_tickets t ON t.relation_sn=n.no_sn AND UPPER(t.company_code)=UPPER(n.company_code) WHERE UPPER(n.company_code)=? AND t.id=? AND n.cutoff_id IS NOT NULL",Long.class,company,ticket))cutoff.process(company,op);
    }))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Purchase receipt wakeup deferred company={} ticket={}",company,ticket);});}
    @PreDestroy public void close(){work.close();}
}
