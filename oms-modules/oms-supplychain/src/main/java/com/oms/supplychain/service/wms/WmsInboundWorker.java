package com.oms.supplychain.service.wms;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
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
    @Scheduled(fixedDelayString="${oms.wms.poll-delay:5000}",initialDelay=15000)
    public void poll(){
        if(!(dataSource instanceof DynamicRoutingDataSource))return;
        DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;
        for(Map.Entry<String,String> entry:tenantRoutes.all().entrySet()){
            try{
                // Never permit a datasource group name to choose a different tenant's pool.
                WmsStore.require(routing.getDataSources().containsKey(entry.getValue())&&routing.getDataSource(entry.getValue())==routing.getDataSources().get(entry.getValue()),"WMS租户数据源不存在或不唯一");
                DynamicDataSourceContextHolder.push(entry.getValue());
                inbound.pollCompany(entry.getKey());
            }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("WMS任务数据源异常，route={}",entry.getKey(),e);}
            finally{DynamicDataSourceContextHolder.clear();}
        }
    }
}
