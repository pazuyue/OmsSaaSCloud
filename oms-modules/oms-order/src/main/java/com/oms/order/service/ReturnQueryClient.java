package com.oms.order.service;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="returnQueryClient",name="oms-supplychain",configuration=OrderShipmentWmsFeignConfig.class)
public interface ReturnQueryClient {
    @GetMapping("/afterSaleReturns/internal/query/document") R<Map<String,Object>> document(@RequestParam("company_code") String c,@RequestParam("sn") String sn,@RequestParam("section") String section,@RequestParam("page") int page,@RequestParam("size") int size,@RequestHeader("from-source") String source);
    @GetMapping("/afterSaleReturns/internal/query/changes") R<List<Map<String,Object>>> changes(@RequestParam("company_code") String c,@RequestHeader("from-source") String source);
    @PostMapping("/afterSaleReturns/internal/query/acknowledge") R<Boolean> acknowledge(@RequestParam("company_code") String c,@RequestBody List<Map<String,Object>> rows,@RequestHeader("from-source") String source);
    @PostMapping("/afterSaleReturns/internal/query/snapshots") R<List<Map<String,Object>>> snapshots(@RequestParam("company_code") String c,@RequestBody List<Map<String,Object>> rows,@RequestHeader("from-source") String source);
}
