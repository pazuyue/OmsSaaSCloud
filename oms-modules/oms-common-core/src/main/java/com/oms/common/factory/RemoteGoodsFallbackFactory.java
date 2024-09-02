package com.oms.common.factory;

import com.oms.common.api.RemoteGoodsService;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.ruoyi.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RemoteGoodsFallbackFactory implements FallbackFactory<RemoteGoodsService> {
    @Override
    public RemoteGoodsService create(Throwable throwable) {
        log.error("OMS商品服务调用失败:{}", throwable.getMessage());
        return (goodsSkuSnInfo, company_code) -> R.fail("OMS商品服务失败:" + throwable.getMessage());
    }
}
