package com.oms.order.service;
import com.oms.common.tenant.TenantRouting;
import com.oms.common.task.ScopedWorkExecutor;
import org.springframework.stereotype.Component;
import javax.annotation.*;
import static com.oms.order.service.OrderValues.*;
@Component
public class OrderImmediateWork {
    @Resource private TenantRouting tenantRouting;
    @Resource private OrderShipmentReceiptService receipts;
    @Resource private OrderSalesDocumentService sales;
    @Resource private OrderCutoffService cutoff;
    public boolean cutoffReady(String company,long order){return work.submit(tenantRouting.captureForContinuation(company),()->cutoff.process(company,order));}
    private final ScopedWorkExecutor work=new ScopedWorkExecutor("order-immediate");
    public void salesChanged(String company,long shipment){if(!work.submit(tenantRouting.captureForContinuation(company),()->sales.sync(company,shipment)))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Sales immediate queue full; durable change retained company={} shipment={}",company,shipment);}
    public boolean receiptReady(String company,long event){require(event>0,"出库事件ID无效");return work.submit(tenantRouting.captureForContinuation(company),()->receipts.ready(company,event));}
    @PreDestroy public void close(){work.close();}
}
