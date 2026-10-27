package com.oms.supplychain.service.wms;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="stockMovementClient",name="oms-inventory") public interface StockMovementClient {
 @PostMapping(value="/stockMovement/internal/{action}",headers="from-source=inner") R<Map<String,Object>> execute(@RequestParam("company_code") String c,@PathVariable("action") String action,@RequestBody Map<String,Object>body);
 @GetMapping(value="/stockMovement/internal/report",headers="from-source=inner") R<Map<String,Object>> report(@RequestParam("company_code") String c,@RequestParam("sn") String sn);
}
