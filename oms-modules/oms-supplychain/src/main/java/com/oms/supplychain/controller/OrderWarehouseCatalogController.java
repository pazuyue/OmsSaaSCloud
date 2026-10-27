package com.oms.supplychain.controller;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orderCatalog/internal")
public class OrderWarehouseCatalogController {
    @Resource private JdbcTemplate jdbc;
    @Resource private com.oms.supplychain.service.wms.WmsCarrierDirectory carrierDirectory;
    @Resource private com.oms.supplychain.service.wms.WmsCarrierBridge carrierBridge;
    @InnerAuth @PostMapping("/carrier-fact-retry") public R<Map<String,Object>> retryFact(@RequestParam("company_code") String company,@RequestBody Map<String,Object> fact){return R.ok(carrierBridge.retryFact(company,String.valueOf(fact.get("business_type")),Long.parseLong(String.valueOf(fact.get("business_id"))),String.valueOf(fact.get("fact_id")),String.valueOf(fact.get("external_code"))));}
    @InnerAuth @PostMapping("/carrier-directory") public R<Map<String,Object>> carrierDirectory(@RequestParam("company_code") String company,@RequestBody Map<String,Object> scope){return R.ok(carrierDirectory.query(company,scope));}
    @InnerAuth @GetMapping("/carrier-context") public R<Map<String,Object>> carrierContext(@RequestParam("company_code") String company){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("connections",jdbc.queryForList("SELECT id,name,provider,enabled FROM wms_connection WHERE company_code=? ORDER BY id LIMIT 501",company));
        out.put("stores",jdbc.queryForList("SELECT DISTINCT v.connection_id,o.owner_code,o.owner_name,w.real_store_code,w.wms_name real_store_name,v.external_owner,v.external_warehouse FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.id=v.owner_warehouse_id AND r.company_key=v.company_key JOIN owner_info o ON o.id=r.owner_id AND o.company_key=r.company_key JOIN wms_real_store_info w ON w.id=r.real_store_id AND w.company_key=r.company_key WHERE v.company_key=? AND v.connection_id IS NOT NULL ORDER BY v.connection_id,o.owner_code,w.real_store_code LIMIT 2001",company));return R.ok(out);
    }
    @InnerAuth @GetMapping("/stores")
    public R<List<Map<String,Object>>> stores(@RequestParam("company_code") String company) {
        return R.ok(jdbc.queryForList("SELECT v.id,v.wms_simulation_code,v.wms_simulation_name,o.owner_code,o.owner_name,v.status,v.outbound_mode,v.connection_id,c.provider,w.real_store_code,w.wms_name real_store_name FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.id=v.owner_warehouse_id AND UPPER(r.company_code)=UPPER(v.company_code) JOIN owner_info o ON o.id=r.owner_id AND UPPER(o.company_code)=UPPER(r.company_code) JOIN wms_real_store_info w ON w.id=r.real_store_id AND UPPER(w.company_code)=UPPER(r.company_code) LEFT JOIN wms_connection c ON c.id=v.connection_id AND c.company_code=UPPER(v.company_code) WHERE UPPER(v.company_code)=UPPER(?) AND v.status=2 AND r.status=2 AND o.is_enable=2 AND w.status=2 ORDER BY v.id",company));
    }
}
