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
    @InnerAuth @GetMapping("/stores")
    public R<List<Map<String,Object>>> stores(@RequestParam("company_code") String company) {
        return R.ok(jdbc.queryForList("SELECT v.id,v.wms_simulation_code,v.wms_simulation_name,o.owner_code,o.owner_name,v.status,v.outbound_mode,w.real_store_code,w.wms_name real_store_name FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.id=v.owner_warehouse_id AND UPPER(r.company_code)=UPPER(v.company_code) JOIN owner_info o ON o.id=r.owner_id AND UPPER(o.company_code)=UPPER(r.company_code) JOIN wms_real_store_info w ON w.id=r.real_store_id AND UPPER(w.company_code)=UPPER(r.company_code) WHERE UPPER(v.company_code)=UPPER(?) AND v.status=2 AND r.status=2 AND o.is_enable=2 AND w.status=2 ORDER BY v.id",company));
    }
}
