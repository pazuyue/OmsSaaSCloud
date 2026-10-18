package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="refundReturnClient",name="oms-supplychain",configuration=OrderShipmentWmsFeignConfig.class)
public interface RefundReturnClient {
    @GetMapping("/refundReturns/internal/parcels/{parcel}/inspect") R<Map<String,Object>> inspectParcel(@RequestParam("company_code") String c,@PathVariable("parcel") long parcel,@RequestParam("channel") long channel,@RequestParam("tid") String tid,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/refundReturns/internal/parcels/capabilities") R<List<Map<String,Object>>> parcelCapabilities(@RequestParam("company_code") String c,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/refundReturns/internal/parcels") R<List<Map<String,Object>>> parcels(@RequestParam("company_code") String c,@RequestParam("refund") long refund,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/refundReturns/internal/parcels/candidates") R<List<Map<String,Object>>> parcelCandidates(@RequestParam("company_code") String c,@RequestParam("carrier") String carrier,@RequestParam("waybill") String waybill,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/refundReturns/internal/parcels/plans/{plan}") R<Map<String,Object>> attachParcel(@RequestParam("company_code") String c,@PathVariable("plan") long plan,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/refundReturns/internal/parcels/{parcel}/transport") R<Map<String,Object>> parcelTransport(@RequestParam("company_code") String c,@RequestParam("refund") long refund,@PathVariable("parcel") long parcel,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/refundReturns/internal/parcels/{parcel}/receipts/{event}") R<Boolean> allocateParcel(@RequestParam("company_code") String c,@RequestParam("refund") long refund,@PathVariable("parcel") long parcel,@PathVariable("event") long event,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/refundReturns/internal/parcels/plans/{plan}/detach") R<Boolean> detachParcel(@RequestParam("company_code") String c,@PathVariable("plan") long plan,@RequestParam("refund") long refund,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/refundReturns/internal/pending") R<List<Map<String,Object>>> pending(@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/refundReturns/internal/{plan}/acknowledge") R<Boolean> acknowledge(@RequestParam("company_code") String company,@PathVariable("plan") long plan,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/refundReturns/internal/{plan}") R<Map<String,Object>> prepare(@RequestParam("company_code") String company,@PathVariable("plan") long plan,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @GetMapping("/refundReturns/internal/{plan}") R<Map<String,Object>> detail(@RequestParam("company_code") String company,@PathVariable("plan") long plan,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/refundReturns/internal/{plan}/documents/{document}/{action}") R<Map<String,Object>> document(@RequestParam("company_code") String company,@PathVariable("plan") long plan,@PathVariable("document") long document,@PathVariable("action") String action,@RequestBody Map<String,Object> body,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
