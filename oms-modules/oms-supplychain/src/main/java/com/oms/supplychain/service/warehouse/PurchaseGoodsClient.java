package com.oms.supplychain.service.warehouse;

import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** Authenticated service-to-service lookup; no browser login context is assumed. */
@FeignClient(contextId="purchaseGoodsClient",value=ServiceNameConstants.OMS_GOODS_SERVICE)
public interface PurchaseGoodsClient {
    @PostMapping("/api/goods/purchaseLookup")
    R<GoodsSkuSnInfo> lookup(@RequestBody GoodsSkuSnInfo filter,@RequestParam("company_code") String company,@RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
