package com.oms.inventory.service.rule;

import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

@FeignClient(contextId="allocationStoreClient", name="oms-supplychain")
public interface AllocationStoreClient {
    @GetMapping("/simulationStore/allocationLookup")
    AjaxResult lookup(@RequestParam("codes") List<String> codes, @RequestParam("keyword") String keyword,
        @RequestParam("company_code") String company,
        @org.springframework.web.bind.annotation.RequestHeader("Authorization") String authorization);
}
