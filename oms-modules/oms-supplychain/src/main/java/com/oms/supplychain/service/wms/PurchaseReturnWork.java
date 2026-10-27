package com.oms.supplychain.service.wms;
import com.oms.common.task.CommittedWork;
import com.oms.common.task.ScopedWorkExecutor;
import com.oms.common.tenant.TenantRouting;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import javax.annotation.*;
@Component public class PurchaseReturnWork {
 @Resource TenantRouting routing; @Resource @Lazy PurchaseReturns service; @Resource @Lazy PurchaseReturnReclaims reclaims;
 private final ScopedWorkExecutor executor=new ScopedWorkExecutor("purchase-return");
 public void ready(String company,long id){CommittedWork.afterCommit(()->{if(!executor.submit(routing.captureForContinuation(company),()->service.process(company,id)))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Purchase return wakeup deferred company={} id={}",company,id);});}
 public void reclaimReady(String company,long id){CommittedWork.afterCommit(()->{if(!executor.submit(routing.captureForContinuation(company),()->reclaims.process(company,id)))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Purchase reclaim wakeup deferred company={} id={}",company,id);});}
 @PreDestroy public void close(){executor.close();}
}
