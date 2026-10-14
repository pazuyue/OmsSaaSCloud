package com.oms.order.service;
import com.oms.common.tenant.TenantRouting;
import com.oms.common.task.ScanReport;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
@Component
public class OrderIssueWorker {
    @Resource private TenantRouting tenantRouting;
    @Resource private OrderIssueService issues;
    public Map<String,Object> poll(){ScanReport report=new ScanReport();for(String company:tenantRouting.companies())try{tenantRouting.run(company,()->report.merge(issues.pollCompany(company)));}catch(RuntimeException e){report.scopeFailure(company);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Company scan unavailable company={}",company);}return report;}
}
