package com.oms.supplychain.service.wms;

import com.oms.common.tenant.TenantRouting;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
import java.util.function.Supplier;
import static com.oms.supplychain.service.wms.WmsStore.require;

/** Explicit company-to-pool routing shared by public callbacks and background jobs. */
@Component
public class WmsTenantRoutes {
    @Resource private TenantRouting tenantRouting;
    public Map<String,String> all(){return tenantRouting.getRoutes();}
    public String datasource(String company){return tenantRouting.capture(company).datasource();}
    public <T> T call(String company,Supplier<T> work){return tenantRouting.call(company,work);}
    public void run(String company,Runnable work){tenantRouting.run(company,work);}
}
