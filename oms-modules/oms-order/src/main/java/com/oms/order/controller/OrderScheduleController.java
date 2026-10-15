package com.oms.order.controller;
import com.oms.order.service.*;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orders/internal/schedule")
public class OrderScheduleController {
    @Resource private OrderShipmentDispatchWorker shipments;
    @Resource private OrderPlatformShippingWorker platform;
    @Resource private OrderIssueWorker issues;
    @Resource private OrderAutoAllocationWorker allocations;
    @InnerAuth @PostMapping("/{kind}") public R<Map<String,Object>> scan(@PathVariable String kind){
        if("allocation".equals(kind))return R.ok(allocations.poll());
        if("platform".equals(kind))return R.ok(platform.poll());if("issues".equals(kind))return R.ok(issues.poll());return R.ok(shipments.scan(kind));
    }
}
