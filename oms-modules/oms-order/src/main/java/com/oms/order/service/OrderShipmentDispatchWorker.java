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
    @Resource private OrderShipmentReceiptService receipts;
    @Resource private OrderSalesDocumentService salesDocuments;
    @Scheduled(fixedDelayString="${oms.order.dispatch-poll-delay:5000}",initialDelay=15000)
    public void poll(){if(!(dataSource instanceof DynamicRoutingDataSource))return;DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;for(Map.Entry<String,DataSource> entry:new TreeMap<>(routing.getDataSources()).entrySet()){
        try{if(routing.getDataSources().get(entry.getKey())!=entry.getValue())continue;DynamicDataSourceContextHolder.push(entry.getKey());JdbcTemplate jdbc=new JdbcTemplate(entry.getValue());jdbc.setQueryTimeout(5);List<Map<String,Object>> due=jdbc.queryForList("SELECT company_code,shipment_id FROM oms_order_shipment_dispatch WHERE state IN ('PENDING','PROCESSING','UNKNOWN') AND next_attempt<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_attempt,id LIMIT 5");for(Map<String,Object> row:due)try{dispatch.process(OrderValues.text(row,"company_code"),OrderValues.number(row,"shipment_id"));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shipment dispatch needs review scope={}/{}",row.get("company_code"),row.get("shipment_id"));}
        List<Map<String,Object>> virtualDue=jdbc.queryForList("SELECT company_code,shipment_id FROM oms_order_shipment_virtual_outbound WHERE state IN ('PENDING','UNKNOWN') AND next_attempt<=NOW() ORDER BY next_attempt,id LIMIT 5");for(Map<String,Object> row:virtualDue)try{virtualOutbound.process(OrderValues.text(row,"company_code"),OrderValues.number(row,"shipment_id"));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Virtual outbound needs review scope={}/{}",row.get("company_code"),row.get("shipment_id"));}
        for(String company:jdbc.queryForList("SELECT DISTINCT company_code FROM oms_order_shipment_dispatch WHERE state NOT IN ('CANCELED')",String.class))receipts.pollCompany(company);
        jdbc.update("INSERT IGNORE INTO oms_order_sales_document_sync(company_code,shipment_id) SELECT company_code,id FROM oms_order_shipment WHERE shipment_sn LIKE 'XS%'");
        for(Map<String,Object> row:jdbc.queryForList("SELECT company_code,shipment_id FROM oms_order_sales_document_sync WHERE next_attempt<=NOW() ORDER BY next_attempt,shipment_id LIMIT 10"))try{salesDocuments.sync(OrderValues.text(row,"company_code"),OrderValues.number(row,"shipment_id"));}catch(RuntimeException e){jdbc.update("UPDATE oms_order_sales_document_sync SET last_error='单据同步快照异常，请核对来源明细',next_attempt=DATE_ADD(NOW(),INTERVAL 30 SECOND) WHERE company_code=? AND shipment_id=?",row.get("company_code"),row.get("shipment_id"));org.slf4j.LoggerFactory.getLogger(getClass()).warn("Sales document sync needs review scope={}/{}",row.get("company_code"),row.get("shipment_id"));}}
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shipment dispatch route unavailable route={}",entry.getKey());}
        finally{DynamicDataSourceContextHolder.poll();}
    }}
}
