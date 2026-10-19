package com.oms.channel.platform;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="stockSyncInventory",name="oms-inventory")
interface StockSyncInventory {
 @PostMapping("/platformStock/internal/snapshot") R<Map<String,Object>> snapshot(@RequestParam("company_code") String company,@RequestBody Map<String,Object> request,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
@FeignClient(contextId="stockSyncOrders",name="oms-order")
interface StockSyncOrders {
 @PostMapping("/orders/internal/stock-sync-guard") R<Map<String,Object>> guard(@RequestParam("company_code") String company,@RequestBody Map<String,Object> request,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
