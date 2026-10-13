package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.*;

@Component
public class OrderIssueWorker {
    @Resource private DataSource dataSource;
    @Resource private OrderIssueService issues;
    @Scheduled(fixedDelayString="${oms.order.issue-poll-delay:30000}",initialDelay=20000)
    public void poll(){if(!(dataSource instanceof DynamicRoutingDataSource))return;DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;for(Map.Entry<String,DataSource> entry:new TreeMap<>(routing.getDataSources()).entrySet())try{if(routing.getDataSources().get(entry.getKey())!=entry.getValue())continue;DynamicDataSourceContextHolder.push(entry.getKey());JdbcTemplate jdbc=new JdbcTemplate(entry.getValue());jdbc.setQueryTimeout(5);for(String company:jdbc.queryForList("SELECT DISTINCT company_code FROM oms_order_pull_config",String.class))issues.pollCompany(company);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order issue route unavailable route={}",entry.getKey());}finally{DynamicDataSourceContextHolder.poll();}}
}
