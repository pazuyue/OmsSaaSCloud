package com.oms.supplychain.service.wms;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="purchaseReturnCarrierClient",name="oms-order") public interface PurchaseReturnCarrierClient {
 @GetMapping(value="/orderCatalog/internal/warehouse-carriers",headers="from-source=inner") R<List<Map<String,Object>>> options(@RequestParam("company_code") String company,@RequestParam("owner") String owner,@RequestParam("warehouse") String warehouse,@RequestParam("province") String province);
}
