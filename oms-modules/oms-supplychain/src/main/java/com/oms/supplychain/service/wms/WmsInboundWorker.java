package com.oms.supplychain.service.wms;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.common.task.ScanReport;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.*;

/** Database tasks survive restarts. Conditional claims coordinate multiple service instances. */
@Component
public class WmsInboundWorker {
    @Resource private DataSource dataSource;
    @Resource private WmsInboundService inbound;
    @Resource private WmsTenantRoutes tenantRoutes;
    public Map<String,Object> poll(){
        ScanReport report=new ScanReport();
        for(Map.Entry<String,String> entry:tenantRoutes.all().entrySet()){
            try{
                tenantRoutes.run(entry.getKey(),()->report.merge(inbound.pollCompany(entry.getKey(),tenantRoutes.enabled(entry.getKey()))));
            }catch(Exception e){report.scopeFailure(entry.getKey());org.slf4j.LoggerFactory.getLogger(getClass()).error("WMS任务数据源异常，route={}",entry.getKey(),e);}
        }
        return report;
    }
}
