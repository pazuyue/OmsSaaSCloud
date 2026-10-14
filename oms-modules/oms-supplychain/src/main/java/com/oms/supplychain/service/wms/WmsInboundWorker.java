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
        if(!(dataSource instanceof DynamicRoutingDataSource))throw new IllegalStateException("仓库动态数据源未配置");
        ScanReport report=new ScanReport();
        DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;
        for(Map.Entry<String,String> entry:tenantRoutes.all().entrySet()){
            try{
                // Never permit a datasource group name to choose a different tenant's pool.
                WmsStore.require(routing.getDataSources().containsKey(entry.getValue())&&routing.getDataSource(entry.getValue())==routing.getDataSources().get(entry.getValue()),"WMS租户数据源不存在或不唯一");
                DynamicDataSourceContextHolder.push(entry.getValue());
                report.merge(inbound.pollCompany(entry.getKey()));
            }catch(Exception e){report.scopeFailure(entry.getKey());org.slf4j.LoggerFactory.getLogger(getClass()).error("WMS任务数据源异常，route={}",entry.getKey(),e);}
            finally{DynamicDataSourceContextHolder.poll();}
        }
        return report;
    }
}
