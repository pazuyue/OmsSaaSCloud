package com.oms.common.tenant;
import com.ruoyi.common.security.annotation.InnerAuth;
import com.ruoyi.common.core.domain.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
/** Fixed authenticated read-only endpoint used by platform onboarding. */
@RestController
public class TenantRoutingProbeController {
    @Resource private TenantRouting tenantRouting;
    @Value("${spring.application.name}") private String service;
    private final java.util.concurrent.Semaphore probes=new java.util.concurrent.Semaphore(2);
    @InnerAuth @PostMapping("/tenantRouting/internal/probe")
    public R<Map<String,Object>> probe(@RequestBody Map<String,String> input){
        if(!probes.tryAcquire())return R.fail("接入检查繁忙，请稍后重试");
        try{
        Map<String,List<String>> tables=new HashMap<>();
        tables.put("oms-order",Arrays.asList("oms_order","oms_order_line","oms_order_shipment","oms_order_pull_config","oms_order_sales_document_sync"));
        tables.put("oms-inventory",Arrays.asList("oms_inventory","oms_channel_inventory","wms_inventory","wms_inventory_batch","rule_stock_order_reservation"));
        tables.put("oms-supplychain",Arrays.asList("wms_tickets","wms_tickets_goods","wms_connection","wms_outbound_task","wms_outbound_receipt_event"));
        tables.put("oms-modules-goods",Arrays.asList("goods_sku_sn_info"));
        tables.put("oms-channel",Arrays.asList("t_channel","channel_platform_app"));
        if(!tables.containsKey(service))throw new IllegalArgumentException("该服务不支持公司接入检查");
        Map<String,Object> result=tenantRouting.inspect(input.get("company_code"),input.get("datasource_alias"),tables.get(service));result.put("service",service);return R.ok(result);
        }finally{probes.release();}
    }
}
