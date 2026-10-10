package com.oms.inventory.controller.wms;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

/** A zero balance does not erase historical warehouse ownership. */
@RestController @RequestMapping("/warehouseReferences")
public class WarehouseReferenceController {
    @Resource private JdbcTemplate jdbc;
    @InnerAuth @PostMapping("/internal")
    public R<List<String>> references(@RequestParam("company_code") String company,@RequestBody List<String> codes) {
        if(company==null || company.trim().isEmpty() || codes.isEmpty() || codes.size()>100) return R.fail("公司或仓库参数无效");
        String placeholders=String.join(",",Collections.nCopies(codes.size(),"?"));
        List<Object> args=new ArrayList<>(); args.add(company.trim().toUpperCase(Locale.ROOT)); args.addAll(codes);
        Set<String> found=new LinkedHashSet<>();
        for(String table:Arrays.asList("wms_inventory","wms_inventory_batch","wms_inventory_change_history","rule_stock_store_code_info"))
            found.addAll(jdbc.queryForList("SELECT DISTINCT store_code FROM "+table+" WHERE UPPER(company_code)=? AND store_code IN ("+placeholders+")",String.class,args.toArray()));
        return R.ok(new ArrayList<>(found));
    }
}
