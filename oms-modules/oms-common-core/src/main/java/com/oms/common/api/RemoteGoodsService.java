package com.oms.common.api;

import com.oms.common.factory.RemoteGoodsFallbackFactory;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteGoodsService", value = ServiceNameConstants.OMS_GOODS_SERVICE, fallbackFactory = RemoteGoodsFallbackFactory.class)
public interface RemoteGoodsService {

    /**
     * 商品查询
     * @param goodsSkuSnInfo
     * @return
     */
    @PostMapping(value = "/api/goods/getOne")
    public R<GoodsSkuSnInfo> selectGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo,@RequestParam("company_code") String company_code);
}
