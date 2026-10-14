package com.oms.supplychain.controller;
import com.oms.supplychain.service.wms.WmsInboundWorker;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/shipmentWms/internal/schedule")
public class WmsScheduleController {
    @Resource private WmsInboundWorker inbound;
    @InnerAuth @PostMapping("/inbound") public R<Map<String,Object>> scan(){return R.ok(inbound.poll());}
}
