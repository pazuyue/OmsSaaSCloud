package com.oms.supplychain.service.wms;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="sharedReturnAuthorizationClient",name="oms-order",configuration=OrderReceiptWakeupConfig.class)
public interface SharedReturnAuthorizationClient {
    @PostMapping("/afterSaleReturns/internal/authorization") R<Map<String,Object>> context(@RequestParam("company_code") String company,@RequestBody List<Map<String,Object>> members,@RequestHeader("from-source") String source);
}
