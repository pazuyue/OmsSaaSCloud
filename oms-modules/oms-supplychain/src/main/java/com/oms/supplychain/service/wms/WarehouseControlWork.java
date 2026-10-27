package com.oms.supplychain.service.wms;
import com.oms.common.task.CommittedWork;
import com.oms.common.task.ScopedWorkExecutor;
import com.oms.common.tenant.TenantRouting;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import javax.annotation.*;
@Component public class WarehouseControlWork {
 @Resource TenantRouting routing;@Resource @Lazy WarehouseTransfers transfers;@Resource @Lazy WarehouseReconciliation reconciliation;
 private final ScopedWorkExecutor transferExecutor=new ScopedWorkExecutor("warehouse-transfer");
 private final ScopedWorkExecutor queryExecutor=new ScopedWorkExecutor("warehouse-reconciliation");
 public void ready(String c,long id,boolean transfer){CommittedWork.afterCommit(()->{ScopedWorkExecutor executor=transfer?transferExecutor:queryExecutor;if(!executor.submit(routing.captureForContinuation(c),()->{if(transfer)transfers.process(c,id);else reconciliation.process(c,id);}))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Warehouse control deferred company={} id={}",c,id);});}
 public void adjustmentReady(String c,long id){CommittedWork.afterCommit(()->{if(!transferExecutor.submit(routing.captureForContinuation(c),()->reconciliation.postAdjustment(c,id)))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Warehouse adjustment deferred company={} id={}",c,id);});}
 @PreDestroy public void close(){transferExecutor.close();queryExecutor.close();}
}
