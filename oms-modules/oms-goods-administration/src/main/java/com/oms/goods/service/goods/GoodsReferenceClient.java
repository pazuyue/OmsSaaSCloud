package com.oms.goods.service.goods;

import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(contextId="goodsReferenceClient", name="oms-inventory")
public interface GoodsReferenceClient {
    @PostMapping("/goodsReferences/internal")
    R<List<String>> references(@RequestParam("company_code") String company, @RequestBody List<String> skus,
        @RequestHeader("from-source") String source);
}
