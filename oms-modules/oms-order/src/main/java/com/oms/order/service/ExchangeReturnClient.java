package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="exchangeReturnClient",name="oms-supplychain",configuration=OrderShipmentWmsFeignConfig.class)
public interface ExchangeReturnClient {
    @PostMapping("/exchangeReturns/internal/{exchange}") R<Map<String,Object>> prepare(@RequestParam("company_code") String company,@PathVariable("exchange") long exchange,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/exchangeReturns/internal/{exchange}") R<Map<String,Object>> detail(@RequestParam("company_code") String company,@PathVariable("exchange") long exchange,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/exchangeReturns/internal/{exchange}/{action}") R<Map<String,Object>> action(@RequestParam("company_code") String company,@PathVariable("exchange") long exchange,@PathVariable("action") String action,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/exchangeReturns/internal/{exchange}/documents/{document}/{action}") R<Map<String,Object>> document(@RequestParam("company_code") String company,@PathVariable("exchange") long exchange,@PathVariable("document") long document,@PathVariable("action") String action,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}

