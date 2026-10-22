package com.oms.order.service;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@FeignClient(contextId="orderWarehouseAllocationClient",name="oms-inventory",configuration=OrderShipmentWmsFeignConfig.class)
public interface OrderWarehouseClient {
    @PostMapping("/orderStock/internal/exchange-stock") R<List<Map<String,Object>>> exchangeStock(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/return-basis") R<Map<String,Object>> returnBasis(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/fulfillment-adjustment") R<Map<String,Object>> adjust(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/orderStock/internal/allocation-stock-signals") R<List<Map<String,Object>>> stockSignals(@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/allocation-stock-signals/ack") R<Map<String,Object>> ackStockSignal(@RequestParam("company_code") String company,@RequestBody Map<String,Object> signal,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/orderStock/internal/warehouse-outbound/{request}") R<Map<String,Object>> warehouseReceipt(@RequestParam("company_code") String company,@PathVariable("request") String request,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/warehouse-outbound") R<Map<String,Object>> warehouseOutbound(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/orderStock/internal/virtual-outbound/{request}")
    R<Map<String,Object>> virtualReceipt(@RequestParam("company_code") String company,@PathVariable("request") String request,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/virtual-outbound")
    R<Map<String,Object>> virtualOutbound(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/orderStock/internal/warehouse-receipts")
    R<List<Map<String,Object>>> receipts(@RequestParam("company_code") String company,@RequestParam("channel_id") long channel,@RequestParam("platform_tid") String tid,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/warehouse-receipts/batch")
    R<List<Map<String,Object>>> receiptsBatch(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/warehouse-allocation-preview")
    R<Map<String,Object>> preview(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderStock/internal/warehouse-allocate")
    R<List<Map<String,Object>>> allocate(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
