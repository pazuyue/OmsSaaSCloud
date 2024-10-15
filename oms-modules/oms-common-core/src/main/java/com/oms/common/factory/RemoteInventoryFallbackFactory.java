package com.oms.common.factory;

import com.oms.common.api.RemoteInventoryService;
import com.ruoyi.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RemoteInventoryFallbackFactory implements FallbackFactory<RemoteInventoryService> {
    @Override
    public RemoteInventoryService create(Throwable throwable) {
        log.error("OMS库存服务调用失败:{}", throwable.getMessage());
        return (WmsInventoryBatch, company_code) -> R.fail("OMS库存服务失败:" + throwable.getMessage());
    }
}
