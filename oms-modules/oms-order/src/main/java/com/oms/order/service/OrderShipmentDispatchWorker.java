package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.*;

@Component
public class OrderShipmentDispatchWorker {
    @Resource private DataSource dataSource;
    @Resource private OrderShipmentDispatchService dispatch;
    @Resource private OrderVirtualOutboundService virtualOutbound;
    @Scheduled(fixedDelayString="${oms.order.dispatch-poll-delay:5000}",initialDelay=15000)
    public void poll(){if(!(dataSource instanceof DynamicRoutingDataSource))return;DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;for(Map.Entry<String,DataSource> entry:new TreeMap<>(routing.getDataSources()).entrySet()){
        try{if(routing.getDataSources().get(entry.getKey())!=entry.getValue())continue;DynamicDataSourceContextHolder.push(entry.getKey());JdbcTemplate jdbc=new JdbcTemplate(entry.getValue());jdbc.setQueryTimeout(5);List<Map<String,Object>> due=jdbc.queryForList("SELECT company_code,shipment_id FROM oms_order_shipment_dispatch WHERE state IN ('PENDING','PROCESSING','UNKNOWN') AND next_attempt<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_attempt,id LIMIT 5");for(Map<String,Object> row:due)try{dispatch.process(OrderValues.text(row,"company_code"),OrderValues.number(row,"shipment_id"));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shipment dispatch needs review scope={}/{}",row.get("company_code"),row.get("shipment_id"));}
        List<Map<String,Object>> virtualDue=jdbc.queryForList("SELECT company_code,shipment_id FROM oms_order_shipment_virtual_outbound WHERE state IN ('PENDING','UNKNOWN') AND next_attempt<=NOW() ORDER BY next_attempt,id LIMIT 5");for(Map<String,Object> row:virtualDue)try{virtualOutbound.process(OrderValues.text(row,"company_code"),OrderValues.number(row,"shipment_id"));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Virtual outbound needs review scope={}/{}",row.get("company_code"),row.get("shipment_id"));}}
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shipment dispatch route unavailable route={}",entry.getKey());}
        finally{DynamicDataSourceContextHolder.poll();}
    }}
}
