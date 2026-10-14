package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.common.task.ScanReport;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Invoked exclusively through the centrally managed Quartz jobs. */
@Component
public class OrderShipmentDispatchWorker {
    @Resource private DataSource dataSource;
    @Resource private OrderShipmentDispatchService dispatch;
    @Resource private OrderVirtualOutboundService virtualOutbound;
    @Resource private OrderShipmentReceiptService receipts;
    @Resource private OrderSalesDocumentService salesDocuments;
    public Map<String,Object> scan(String kind){
        require(Arrays.asList("dispatch","receipts","virtual","sales").contains(kind),"扫描类型无效");
        require(dataSource instanceof DynamicRoutingDataSource,"订单动态数据源未配置");
        ScanReport report=new ScanReport();DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;
        for(Map.Entry<String,DataSource> entry:new TreeMap<>(routing.getDataSources()).entrySet()){
            try{if(routing.getDataSources().get(entry.getKey())!=entry.getValue())continue;DynamicDataSourceContextHolder.push(entry.getKey());JdbcTemplate db=new JdbcTemplate(entry.getValue());db.setQueryTimeout(5);
                if("receipts".equals(kind)){for(String company:db.queryForList("SELECT DISTINCT company_code FROM oms_order_shipment_dispatch WHERE state<>'CANCELED'",String.class))try{report.merge(receipts.pollCompany(company));}catch(RuntimeException e){report.scopeFailure(company);}continue;}
                String table="dispatch".equals(kind)?"oms_order_shipment_dispatch":"virtual".equals(kind)?"oms_order_shipment_virtual_outbound":"oms_order_sales_document_sync";
                if("sales".equals(kind))db.update("INSERT IGNORE INTO oms_order_sales_document_sync(company_code,shipment_id) SELECT company_code,id FROM oms_order_shipment WHERE shipment_sn LIKE 'XS%'");
                String due="dispatch".equals(kind)?"t.state IN ('PENDING','PROCESSING','UNKNOWN') AND (t.lease_until IS NULL OR t.lease_until<NOW())":"virtual".equals(kind)?"t.state IN ('PENDING','UNKNOWN')":"1=1";
                List<Map<String,Object>> rows=db.queryForList("SELECT t.company_code,t.shipment_id,s.shipment_sn FROM "+table+" t JOIN oms_order_shipment s ON s.company_code=t.company_code AND s.id=t.shipment_id WHERE "+due+" AND t.next_attempt<=NOW() ORDER BY t.next_attempt,t.shipment_id LIMIT "+("sales".equals(kind)?10:5));
                for(Map<String,Object> row:rows){String company=text(row,"company_code"),state="PENDING";long id=number(row,"shipment_id");try{
                    if("dispatch".equals(kind))dispatch.process(company,id);else if("virtual".equals(kind))virtualOutbound.process(company,id);else salesDocuments.sync(company,id);
                    Map<String,Object> result=db.queryForMap("SELECT * FROM "+table+" WHERE company_code=? AND shipment_id=? ORDER BY "+("virtual".equals(kind)?"id DESC":"shipment_id")+" LIMIT 1",company,id);
                    state="sales".equals(kind)?(!text(result,"last_error").isEmpty()?"FAILED":number(result,"synced_revision")>=number(result,"revision")?"SUCCESS":"PENDING"):Arrays.asList("ACCEPTED","COMPLETED").contains(text(result,"state"))?"SUCCESS":"FAILED".equals(text(result,"state"))?"FAILED":"UNKNOWN".equals(text(result,"state"))?"UNKNOWN":"PENDING";
                }catch(RuntimeException e){state="FAILED";if("sales".equals(kind))db.update("UPDATE oms_order_sales_document_sync SET last_error='单据同步未确认，请核对来源明细',next_attempt=DATE_ADD(NOW(),INTERVAL 30 SECOND) WHERE company_code=? AND shipment_id=?",company,id);}
                    report.record(company,id,text(row,"shipment_sn"),state,"SUCCESS".equals(state)?("dispatch".equals(kind)?"仓库已受理下发，实际出库由回传更新":"本次处理已完成"):"请打开原单查看任务状态及处理原因");
                }
            }catch(RuntimeException e){report.scopeFailure(entry.getKey());org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order scan unavailable kind={} route={}",kind,entry.getKey());}finally{DynamicDataSourceContextHolder.poll();}
        }return report;
    }
}
