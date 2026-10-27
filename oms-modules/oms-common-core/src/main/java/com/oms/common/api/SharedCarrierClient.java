package com.oms.common.api;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@FeignClient(contextId="sharedCarrierClient",name="oms-order")
public interface SharedCarrierClient {
    @GetMapping("/sharedCarriers/internal/standards") R<Map<String,Object>> standards(@RequestParam("company_code") String company,@RequestParam("keyword") String keyword,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/sharedCarriers/internal/standard/{id}") R<Map<String,Object>> standard(@RequestParam("company_code") String company,@PathVariable("id") long id,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/sharedCarriers/internal/options") R<Map<String,Object>> options(@RequestParam("company_code") String company,@RequestBody Map<String,Object> context,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/sharedCarriers/internal/convert") R<Map<String,Object>> convert(@RequestParam("company_code") String company,@RequestBody Map<String,Object> context,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/sharedCarriers/internal/freeze/{request}") R<Map<String,Object>> freeze(@RequestParam("company_code") String company,@PathVariable("request") String request,@RequestBody Map<String,Object> context,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/sharedCarriers/internal/facts") R<Map<String,Object>> fact(@RequestParam("company_code") String company,@RequestBody Map<String,Object> fact,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
