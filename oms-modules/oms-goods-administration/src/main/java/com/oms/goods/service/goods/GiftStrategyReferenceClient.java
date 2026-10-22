package com.oms.goods.service.goods;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@FeignClient(contextId="giftStrategyReferenceClient",name="oms-order")
public interface GiftStrategyReferenceClient {
    @PostMapping("/giftCatalog/internal/references")
    R<Map<String,Object>> inspect(@RequestParam("company_code") String company,@RequestBody Map<String,Object> query,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
