package com.oms.goods.service.goods.impl;


import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.goods.mapper.GoodsSkuSnInfoMapper;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;
import com.oms.goods.service.goods.GoodsSkuSnInfoService;
import com.ruoyi.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

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

    @Override
    public GoodsSkuSnInfo selectGoodsSkuSnInfoById(Long id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<GoodsSkuSnInfo> selectGoodsSkuSnInfoList(GoodsSkuSnInfo goodsSkuSnInfo) {
        QueryWrapper<GoodsSkuSnInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getCompanyCode()),"company_code", goodsSkuSnInfo.getCompanyCode());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getSkuSn()),"sku_sn",goodsSkuSnInfo.getSkuSn());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getGoodsSn()),"goods_sn",goodsSkuSnInfo.getGoodsSn());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSkuSnInfo.getBarcodeSn()),"barcode_sn",goodsSkuSnInfo.getBarcodeSn());
        queryWrapper.like(StringUtils.isNotEmpty(goodsSkuSnInfo.getGoodsName()),"goods_name",goodsSkuSnInfo.getGoodsName());
        queryWrapper.like(ObjectUtil.isNotEmpty(goodsSkuSnInfo.getIsGift()),"is_gift",goodsSkuSnInfo.getIsGift());
        queryWrapper.like(ObjectUtil.isNotEmpty(goodsSkuSnInfo.getIsFd()),"is_fd",goodsSkuSnInfo.getIsFd());
        queryWrapper.like(ObjectUtil.isNotEmpty(goodsSkuSnInfo.getIsPackage()),"is_package",goodsSkuSnInfo.getIsPackage());
        return this.list(queryWrapper);
    }

    @Override
    public int insertGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo) {
        return 0;
    }

    @Override
    public int updateGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo) {
        return this.baseMapper.updateById(goodsSkuSnInfo);
    }

    @Override
    public int deleteGoodsSkuSnInfoByIds(Long[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteGoodsSkuSnInfoById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
