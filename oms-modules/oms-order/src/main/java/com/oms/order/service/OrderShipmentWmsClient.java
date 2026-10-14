package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@FeignClient(contextId="orderShipmentWmsClient",name="oms-supplychain",configuration=OrderShipmentWmsFeignConfig.class)
public interface OrderShipmentWmsClient {
    @PostMapping("/shipmentWms/internal/review-summaries") R<List<Map<String,Object>>> reviewSummaries(@RequestParam("company_code") String company,@RequestBody List<Long> shipments,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/shipmentWms/internal/reviews") R<List<Map<String,Object>>> reviews(@RequestParam("company_code") String company,@RequestParam("shipment_id") long shipment,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/reviews/{review}") R<Map<String,Object>> review(@RequestParam("company_code") String company,@RequestParam("shipment_id") long shipment,@PathVariable("review") long review,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/sales-document") R<Boolean> salesDocument(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/shipmentWms/internal/receipt-event") R<Map<String,Object>> receiptEvent(@RequestParam("company_code") String company,@RequestParam("event_id") long event,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/shipmentWms/internal/receipt-scan") R<Map<String,Object>> receiptScan(@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/shipmentWms/internal/receipts") R<List<Map<String,Object>>> receipts(@RequestParam("company_code") String company,@RequestParam("shipment_sn") String sn,@RequestParam("include_failed") boolean failed,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/receipts/{event}/result") R<Boolean> receiptResult(@RequestParam("company_code") String company,@PathVariable("event") long event,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/resolve") R<Map<String,Object>> resolve(@RequestParam("company_code") String company,@RequestBody Map<String,Object> payload,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/submit") R<Map<String,Object>> submit(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/shipmentWms/internal/query") R<Map<String,Object>> query(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
