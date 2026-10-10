package com.oms.supplychain.service.warehouse;

import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(contextId="warehouseReferenceClient",name="oms-inventory")
public interface WarehouseReferenceClient {
    @PostMapping("/warehouseReferences/internal")
    R<List<String>> references(@RequestParam("company_code") String company,@RequestBody List<String> codes,@RequestHeader("from-source") String source);
}
