package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.oms.common.task.ScanReport;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.*;

@Component
public class OrderPlatformShippingWorker {
    @Resource private DataSource dataSource;
    @Resource private OrderPlatformShippingService shipping;
    public Map<String,Object> poll(){if(!(dataSource instanceof DynamicRoutingDataSource))throw new IllegalStateException("订单动态数据源未配置");ScanReport report=new ScanReport();DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;for(Map.Entry<String,DataSource> entry:new TreeMap<>(routing.getDataSources()).entrySet())try{if(routing.getDataSources().get(entry.getKey())!=entry.getValue())continue;DynamicDataSourceContextHolder.push(entry.getKey());JdbcTemplate db=new JdbcTemplate(entry.getValue());db.setQueryTimeout(5);for(String company:db.queryForList("SELECT DISTINCT company_code FROM oms_order_pull_config",String.class))report.merge(shipping.pollCompany(company));}catch(RuntimeException e){report.scopeFailure(entry.getKey());org.slf4j.LoggerFactory.getLogger(getClass()).warn("Platform shipping route temporarily unavailable route={}",entry.getKey());}finally{DynamicDataSourceContextHolder.poll();}return report;}
}
