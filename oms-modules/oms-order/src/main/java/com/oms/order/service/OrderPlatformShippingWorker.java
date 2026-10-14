package com.oms.order.service;
import com.oms.common.tenant.TenantRouting;
import com.oms.common.task.ScanReport;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
@Component
public class OrderPlatformShippingWorker {
    @Resource private TenantRouting tenantRouting;
    @Resource private OrderPlatformShippingService shipping;
    public Map<String,Object> poll(){ScanReport report=new ScanReport();for(String company:tenantRouting.companies())try{tenantRouting.run(company,()->report.merge(shipping.pollCompany(company)));}catch(RuntimeException e){report.scopeFailure(company);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Company scan unavailable company={}",company);}return report;}
}
