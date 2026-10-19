package com.oms.supplychain.service.wms;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="processStockClient",name="oms-inventory") public interface ProcessStockClient {
 @PostMapping(value="/stockProcess/internal/{action}",headers="from-source=inner") R<Map<String,Object>> execute(@RequestParam("company_code") String company,@PathVariable("action") String action,@RequestBody Map<String,Object> body);
 @GetMapping(value="/stockProcess/internal/report",headers="from-source=inner") R<Map<String,Object>> report(@RequestParam("company_code") String company,@RequestParam("sn") String sn);
 @GetMapping(value="/stockProcess/internal/origin",headers="from-source=inner") R<Map<String,Object>> origin(@RequestParam("company_code") String company,@RequestParam("batch") long batch);
 @GetMapping(value="/stockProcess/internal/batches",headers="from-source=inner") R<List<Map<String,Object>>> batches(@RequestParam("company_code") String company,@RequestParam("sku") String sku,@RequestParam("store") String store);
}
