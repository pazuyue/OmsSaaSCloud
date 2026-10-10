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
    @Scheduled(fixedDelayString="${oms.wms.poll-delay:5000}",initialDelay=15000)
    public void poll(){
        if(!(dataSource instanceof DynamicRoutingDataSource))return;
        DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;
        for(Map.Entry<String,DataSource> entry:new TreeMap<>(routing.getDataSources()).entrySet()){
            try{
                // Never permit a datasource group name to choose a different tenant's pool.
                if(routing.getDataSource(entry.getKey())!=entry.getValue())continue;
                JdbcTemplate jdbc=new JdbcTemplate(entry.getValue());
                if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='wms_inbound_task'",Integer.class)!=1)continue;
                DynamicDataSourceContextHolder.push(entry.getKey());
                for(String company:jdbc.queryForList("SELECT DISTINCT company_code FROM wms_inbound_task ORDER BY company_code",String.class)){
                    try{inbound.pollCompany(company);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("WMS入库任务失败，company={}",company,e);}
                }
            }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("WMS任务数据源异常，route={}",entry.getKey(),e);}
            finally{DynamicDataSourceContextHolder.clear();}
        }
    }
}
