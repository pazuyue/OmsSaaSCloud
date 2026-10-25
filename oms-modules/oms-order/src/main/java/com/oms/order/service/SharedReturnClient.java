package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="sharedReturnClient",name="oms-supplychain",configuration=OrderShipmentWmsFeignConfig.class)
public interface SharedReturnClient {
    @PostMapping("/afterSaleReturns/internal/{kind}/{id}") R<Map<String,Object>> advance(@RequestParam("company_code") String company,@PathVariable("kind") String kind,@PathVariable("id") long id,@RequestBody Map<String,Object> input,@RequestHeader("from-source") String source);
    @GetMapping("/afterSaleReturns/internal/{kind}/{id}") R<Map<String,Object>> report(@RequestParam("company_code") String company,@PathVariable("kind") String kind,@PathVariable("id") long id,@RequestHeader("from-source") String source);
    @PostMapping("/afterSaleReturns/internal/{kind}/{id}/{action}") R<Map<String,Object>> action(@RequestParam("company_code") String company,@PathVariable("kind") String kind,@PathVariable("id") long id,@PathVariable("action") String action,@RequestBody Map<String,Object> input,@RequestHeader("from-source") String source);
}
