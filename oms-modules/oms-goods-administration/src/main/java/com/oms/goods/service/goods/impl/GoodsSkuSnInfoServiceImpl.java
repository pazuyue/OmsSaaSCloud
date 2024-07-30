package com.oms.goods.service.goods.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.goods.mapper.GoodsSkuSnInfoMapper;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;
import com.oms.goods.service.goods.GoodsSkuSnInfoService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 产品信息表 服务实现类
 * </p>
 *
 * @author 月光
 * @since 2024-07-30
 */
@Service
public class GoodsSkuSnInfoServiceImpl extends ServiceImpl<GoodsSkuSnInfoMapper, GoodsSkuSnInfo> implements GoodsSkuSnInfoService {

    @Override
    public boolean toExamine(String importBatch) {
        return false;
    }
}
