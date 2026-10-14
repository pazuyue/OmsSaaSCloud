package com.oms.order.service;
import com.oms.common.tenant.TenantRouting;
import com.oms.common.task.ScopedWorkExecutor;
import org.springframework.stereotype.Component;
import javax.annotation.*;
import javax.sql.DataSource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
@Component
public class OrderImmediateWork {
    @Resource private TenantRouting tenantRouting;
    @Resource private OrderShipmentReceiptService receipts;
    @Resource private OrderSalesDocumentService sales;
    private final ScopedWorkExecutor work=new ScopedWorkExecutor("order-immediate");
    public void salesChanged(String company,String ignoredRequestAlias,long shipment){if(!work.submit(tenantRouting.capture(company),()->sales.sync(company,shipment)))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Sales immediate queue full; durable change retained company={} shipment={}",company,shipment);}
    public boolean receiptReady(String company,long event){require(event>0,"出库事件ID无效");return work.submit(tenantRouting.capture(company),()->receipts.ready(company,event));}
    @PreDestroy public void close(){work.close();}
}
