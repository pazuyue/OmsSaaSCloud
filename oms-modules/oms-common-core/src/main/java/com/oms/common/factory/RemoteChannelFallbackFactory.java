package com.oms.common.factory;

import com.oms.common.api.RemoteChannelService;
import com.oms.common.api.RemoteGoodsService;
import com.ruoyi.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RemoteChannelFallbackFactory  implements FallbackFactory<RemoteChannelService> {

    @Override
    public RemoteChannelService create(Throwable throwable) {
        log.error("OMS店铺服务调用失败:{}", throwable.getMessage());
        return (tChannel,company_code) -> R.fail("OMS店铺服务失败:" + throwable.getMessage());
    }
}
