package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.common.task.ScopedWorkExecutor;
import org.springframework.stereotype.Component;
import javax.annotation.*;
import javax.sql.DataSource;
import static com.oms.order.service.OrderValues.*;
@Component
public class OrderImmediateWork {
    @Resource private DataSource dataSource;
    @Resource private OrderShipmentReceiptService receipts;
    @Resource private OrderSalesDocumentService sales;
    private final ScopedWorkExecutor work=new ScopedWorkExecutor("order-immediate");
    private Runnable scoped(String route,Runnable work){require(dataSource instanceof DynamicRoutingDataSource,"订单动态数据源未配置");DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;DataSource pool=routing.getDataSources().get(route);require(pool!=null&&routing.getDataSource(route)==pool,"订单租户路由不存在或不唯一");return ()->{require(routing.getDataSources().get(route)==pool&&routing.getDataSource(route)==pool,"订单租户路由已变更，等待补偿");work.run();};}
    public void salesChanged(String company,String route,long shipment){if(!work.submit(route,scoped(route,()->sales.sync(company,shipment))))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Sales immediate queue full; durable change retained company={} shipment={}",company,shipment);}
    public boolean receiptReady(String company,long event){require(event>0,"出库事件ID无效");String route=DynamicDataSourceContextHolder.peek();return work.submit(route,scoped(route,()->receipts.ready(company,event)));}
    @PreDestroy public void close(){work.close();}
}
