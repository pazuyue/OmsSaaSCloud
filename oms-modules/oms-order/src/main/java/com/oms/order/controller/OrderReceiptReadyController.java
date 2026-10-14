package com.oms.order.controller;
import com.oms.order.service.OrderImmediateWork;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orders/internal")
public class OrderReceiptReadyController {
    @Resource private OrderImmediateWork work;
    @InnerAuth @PostMapping("/receipt-ready") public R<Map<String,Object>> ready(@RequestParam("company_code") String company,@RequestParam("event_id") long event){if(!company.matches("[A-Za-z0-9_-]{1,50}")||event<=0)throw new IllegalArgumentException("出库通知身份无效");return R.ok(Collections.singletonMap("accepted",work.receiptReady(company.toUpperCase(Locale.ROOT),event)));}
}
