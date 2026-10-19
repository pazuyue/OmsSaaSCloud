package com.oms.inventory.controller.wms.api;
import com.oms.inventory.service.impl.PlatformStockSnapshot;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/platformStock/internal") public class PlatformStockSnapshotController {
 @Resource PlatformStockSnapshot service;
 @InnerAuth @PostMapping("/snapshot") public R<Map<String,Object>> snapshot(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){return R.ok(service.snapshot(company,body));}
}
