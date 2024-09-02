package com.ruoyi.goods.api.factory;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.goods.api.RemoteGoodsService;
import com.ruoyi.goods.api.model.GoodsSkuSnInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Component
public class RemoteGoodsFallbackFactory  implements FallbackFactory<RemoteGoodsService> {
    @Override
    public RemoteGoodsService create(Throwable throwable) {
        log.error("OMS商品服务调用失败:{}", throwable.getMessage());
        return new RemoteGoodsService()
        {
            @Override
            public R<GoodsSkuSnInfo> list(GoodsSkuSnInfo goodsSkuSnInfo) {
                return R.fail("OMS商品服务失败:" + throwable.getMessage());
            }
        };
    }
}
