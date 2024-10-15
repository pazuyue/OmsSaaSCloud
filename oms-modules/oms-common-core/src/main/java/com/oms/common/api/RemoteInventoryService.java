package com.oms.common.api;

import com.oms.common.factory.RemoteGoodsFallbackFactory;
import com.oms.common.factory.RemoteInventoryFallbackFactory;
import com.oms.supplychain.model.entity.Inventory.WmsInventoryBatch;
import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteInventoryService", value = ServiceNameConstants.OMS_INVENTORY_SERVICE, fallbackFactory = RemoteInventoryFallbackFactory.class)
public interface RemoteInventoryService {


    /**
     * 库存添加
     * @param inventoryBatch
     * @param company_code
     * @return
     */
    @PostMapping(value = "/api/inventory/addInventory")
    public R<Boolean> addInventory(@RequestBody WmsInventoryBatch inventoryBatch, @RequestParam("company_code") String company_code);
}
