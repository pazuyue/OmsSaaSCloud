package com.ruoyi.goods.api;

import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.goods.api.factory.RemoteGoodsFallbackFactory;
import com.ruoyi.goods.api.model.GoodsSkuSnInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(contextId = "remoteGoodsService", value = ServiceNameConstants.OMS_GOODS_SERVICE, fallbackFactory = RemoteGoodsFallbackFactory.class)
public interface RemoteGoodsService {

    /**
     * 商品查询
     * @param goodsSkuSnInfo
     * @return
     */
    @PostMapping(value = "/info/list", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<GoodsSkuSnInfo> list(GoodsSkuSnInfo goodsSkuSnInfo);
}
