package com.oms.supplychain.service.wms;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="purchaseReturnStockClient",name="oms-inventory") public interface PurchaseReturnStockClient {
 @PostMapping(value="/stockCare/internal/{action}",headers="from-source=inner") R<Map<String,Object>> execute(@RequestParam("company_code") String c,@PathVariable("action") String action,@RequestBody Map<String,Object> body);
 @GetMapping(value="/stockCare/internal/report",headers="from-source=inner") R<Map<String,Object>> report(@RequestParam("company_code") String c,@RequestParam("sn") String sn);
 @PostMapping(value="/stockCare/internal/balances",headers="from-source=inner") R<List<Map<String,Object>>> balances(@RequestParam("company_code") String c,@RequestBody List<Map<String,Object>> keys);
}
