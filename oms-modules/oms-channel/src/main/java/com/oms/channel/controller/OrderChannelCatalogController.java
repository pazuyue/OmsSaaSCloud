package com.oms.channel.controller;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orderCatalog/internal")
public class OrderChannelCatalogController {
    @Resource private JdbcTemplate jdbc;
    @Resource private com.oms.channel.platform.PlatformProductService products;
    @InnerAuth @PostMapping("/product-bindings") public R<List<Map<String,Object>>> bindings(@RequestParam("company_code")String company,@RequestParam("channel_id")long channel,@RequestBody List<Map<String,Object>> identities){return R.ok(products.resolve(company,channel,identities));}
    @InnerAuth @GetMapping("/channels")
    public R<List<Map<String,Object>>> channels(@RequestParam("company_code") String company) {
        return R.ok(jdbc.queryForList("SELECT c.channel_id,c.channel_name,c.channel_type,c.enabled,b.seller_nick,b.expected_shop_id,b.app_id connection_id,a.name connection_name FROM t_channel c LEFT JOIN channel_platform_binding b ON b.channel_id=c.channel_id AND b.company_code=c.company_code LEFT JOIN channel_platform_app a ON a.company_code=b.company_code AND a.id=b.app_id WHERE UPPER(c.company_code)=UPPER(?) ORDER BY c.channel_id",company));
    }
}
