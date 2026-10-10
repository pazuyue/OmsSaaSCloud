package com.oms.supplychain.service.wms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.require;

/** Explicit company-to-pool routing shared by public callbacks and background jobs. */
@Component
public class WmsTenantRoutes {
    @Value("${oms.wms.tenant-routes:}") private String configured;
    private Map<String,String> routes=Collections.emptyMap();
    @PostConstruct public void initialize(){
        Map<String,String> values=new TreeMap<>();
        if(configured!=null&&!configured.trim().isEmpty())for(String mapping:configured.split(",")){
            String[] pair=mapping.trim().split("=",-1);
            require(pair.length==2&&pair[0].matches("[A-Za-z0-9_-]{1,64}")&&pair[1].matches("[A-Za-z0-9_-]{1,64}"),"WMS租户路由格式应为 COMPANY=datasource，以逗号分隔");
            require(values.put(pair[0].toUpperCase(Locale.ROOT),pair[1])==null,"WMS租户路由重复");
        }
        routes=Collections.unmodifiableMap(values);
    }
    public Map<String,String> all(){return routes;}
    public String datasource(String company){String route=routes.get(company.toUpperCase(Locale.ROOT));require(route!=null,"未配置该公司的WMS租户路由");return route;}
}
