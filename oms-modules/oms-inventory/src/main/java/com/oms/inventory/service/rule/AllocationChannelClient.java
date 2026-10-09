package com.oms.inventory.service.rule;

import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

@FeignClient(contextId="allocationChannelClient", name="oms-channel")
public interface AllocationChannelClient {
    @GetMapping("/channel/allocationLookup")
    AjaxResult lookup(@RequestParam("ids") List<Integer> ids, @RequestParam("keyword") String keyword,
        @RequestParam("company_code") String company,
        @org.springframework.web.bind.annotation.RequestHeader("Authorization") String authorization);
}
