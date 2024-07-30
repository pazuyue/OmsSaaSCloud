package com.oms.goods.service.goods;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;

/**
 * <p>
 * 产品信息表 服务类
 * </p>
 *
 * @author 月光
 * @since 2024-07-30
 */
public interface GoodsSkuSnInfoService extends IService<GoodsSkuSnInfo> {

    /**
     * 审核
     * @param importBatch
     * @return
     */
    public boolean toExamine(String importBatch);

}
