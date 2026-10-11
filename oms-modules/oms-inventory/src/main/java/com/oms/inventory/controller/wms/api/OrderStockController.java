package com.oms.inventory.controller.wms.api;
import com.oms.common.model.dto.OrderStockCommand;
import com.oms.inventory.service.impl.OrderStockService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orderStock/internal")
public class OrderStockController {
    @Resource private OrderStockService service;
    @InnerAuth @GetMapping("/demands")
    public R<List<Map<String,Object>>> demands(@RequestParam("company_code") String company,@RequestParam("channel_id") long channel,@RequestParam("platform_tid") String tid){return R.ok(service.demands(company,channel,tid));}
    @InnerAuth @PostMapping("/{action}")
    public R<List<Map<String,Object>>> execute(@PathVariable String action,@RequestParam("company_code") String company,@RequestBody OrderStockCommand command){return R.ok(service.execute(company,action,command));}
}
