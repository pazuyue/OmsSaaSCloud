package com.oms.goods.controller;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orderCatalog/internal")
public class OrderGoodsCatalogController {
    @Resource private JdbcTemplate jdbc;
    @InnerAuth @PostMapping("/goods")
    public R<List<Map<String,Object>>> goods(@RequestParam("company_code") String company,@RequestBody List<String> skus) {
        if(skus==null||skus.size()>500)throw new IllegalArgumentException("单次商品查询最多500个SKU");
        if(skus.isEmpty())return R.ok(Collections.emptyList());
        List<Object> args=new ArrayList<>();args.add(company);args.addAll(skus);
        return R.ok(jdbc.queryForList("SELECT id,sku_sn,goods_name,is_fd,is_package,is_gift FROM goods_sku_sn_info WHERE UPPER(company_code)=UPPER(?) AND sku_sn IN ("+String.join(",",Collections.nCopies(skus.size(),"?"))+")",args.toArray()));
    }
}
