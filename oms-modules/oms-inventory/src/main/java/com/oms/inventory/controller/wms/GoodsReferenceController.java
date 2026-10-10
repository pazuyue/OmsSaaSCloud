package com.oms.inventory.controller.wms;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

/** Internal, read-only deletion guard. Historical references remain meaningful at zero stock. */
@RestController
@RequestMapping("/goodsReferences")
public class GoodsReferenceController {
    @Resource private JdbcTemplate jdbc;
    @InnerAuth
    @PostMapping("/internal")
    public R<List<String>> references(@RequestParam("company_code") String company,@RequestBody List<String> skus) {
        if(company==null || company.trim().isEmpty() || skus.isEmpty() || skus.size()>100)return R.fail("公司或 SKU 参数无效");
        String placeholders=String.join(",",Collections.nCopies(skus.size(),"?"));
        List<Object> args=new ArrayList<>();args.add(company.trim().toUpperCase(Locale.ROOT));args.addAll(skus);Set<String> found=new LinkedHashSet<>();
        for(String table:Arrays.asList("oms_inventory","wms_inventory","wms_inventory_batch","wms_inventory_change_history","oms_channel_inventory","rule_stock_goods_info","rule_stock_reservation","rule_stock_order_reservation","rule_stock_reservation_event"))
            found.addAll(jdbc.queryForList("SELECT DISTINCT sku_sn FROM "+table+" WHERE UPPER(company_code)=? AND sku_sn IN ("+placeholders+")",String.class,args.toArray()));
        found.addAll(jdbc.queryForList("SELECT DISTINCT i.sku_sn FROM rule_stock_daily_item i JOIN rule_stock_daily_run r ON r.id=i.run_id WHERE UPPER(r.company_code)=? AND i.sku_sn IN ("+placeholders+")",String.class,args.toArray()));
        return R.ok(new ArrayList<>(found));
    }
}
