package com.oms.common.api;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(contextId="orderGoodsClient",name="oms-modules-goods")
public interface OrderGoodsClient {
    @PostMapping("/orderCatalog/internal/search")
    R<Map<String,Object>> search(@RequestParam("company_code") String company,@RequestBody Map<String,Object> query,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
    @PostMapping("/orderCatalog/internal/goods")
    R<List<Map<String,Object>>> goods(@RequestParam("company_code") String company,@RequestBody List<String> skus,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
