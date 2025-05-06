package com.oms.common.api;

import com.oms.common.factory.RemoteInventoryFallbackFactory;
import com.oms.common.model.dto.wms.WmsInventoryBatchDto;
import com.oms.common.model.entity.wms.WmsInventoryBatch;
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
     * @param dto
     * @param company_code
     * @return
     */
    @PostMapping(value = "/api/WmsInventory/addInventory")
    public R<Boolean> addInventory(@RequestBody WmsInventoryBatchDto dto, @RequestParam("company_code") String company_code);
}
