package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.common.task.ScopedWorkExecutor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import javax.annotation.*;
import javax.sql.DataSource;
import static com.oms.order.service.OrderValues.*;
@Component
public class OrderImmediateWork {
    @Resource private DataSource dataSource;
    @Resource private OrderShipmentReceiptService receipts;
    @Resource private OrderSalesDocumentService sales;
    private final ScopedWorkExecutor receiptWork=new ScopedWorkExecutor("receipt-immediate"),salesWork=new ScopedWorkExecutor("sales-immediate");
    private Runnable scoped(String route,Runnable work){require(dataSource instanceof DynamicRoutingDataSource,"订单动态数据源未配置");DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;DataSource pool=routing.getDataSources().get(route);require(pool!=null&&routing.getDataSource(route)==pool,"订单租户路由不存在或不唯一");return ()->{require(routing.getDataSources().get(route)==pool&&routing.getDataSource(route)==pool,"订单租户路由已变更，等待补偿");work.run();};}
    @EventListener public void changed(OrderShipmentChanged event){if(!salesWork.submit(event.route,scoped(event.route,()->sales.sync(event.company,event.shipment))))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Sales immediate queue full; durable change retained company={} shipment={}",event.company,event.shipment);}
    public boolean receiptReady(String company,long event){require(event>0,"出库事件ID无效");String route=DynamicDataSourceContextHolder.peek();return receiptWork.submit(route,scoped(route,()->receipts.ready(company,event)));}
    @PreDestroy public void close(){receiptWork.close();salesWork.close();}
}
