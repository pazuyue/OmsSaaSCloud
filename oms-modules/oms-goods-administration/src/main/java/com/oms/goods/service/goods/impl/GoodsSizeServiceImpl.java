package com.oms.goods.service.goods.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.goods.mapper.GoodsSizeMapper;
import com.oms.goods.mapper.GoodsSkuSnInfoTmpMapper;
import com.oms.goods.model.entity.goods.GoodsSize;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfoTmp;
import com.oms.goods.service.goods.GoodsSizeService;
import com.oms.goods.service.goods.GoodsSkuSnInfoTmpService;
import com.ruoyi.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

@Service
public class GoodsSizeServiceImpl extends ServiceImpl<GoodsSizeMapper, GoodsSize> implements GoodsSizeService  {

    @Override
    public Integer selectOrSaveBySizeName(String size_name,String company_code) {
        QueryWrapper<GoodsSize> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("size_name",size_name);
        GoodsSize goodsSize = this.getBaseMapper().selectOne(queryWrapper);
        if (ObjectUtil.isEmpty(goodsSize)){
            GoodsSize size = new GoodsSize();
            size.setSizeName(size_name);
            size.setCompanyCode(company_code);
            return this.getBaseMapper().insert(size);
        }
        return goodsSize.getId();
    }

    @Override
    public GoodsSize selectGoodsSizeById(Integer id) {
        return this.getById(id);
    }

    @Override
    public List<GoodsSize> selectGoodsSizeList(GoodsSize goodsSize) {
        QueryWrapper<GoodsSize> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSize.getCompanyCode()),"company_code", goodsSize.getCompanyCode());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSize.getOutSizeCode()),"out_size_code",goodsSize.getOutSizeCode());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsSize.getSizeName()),"size_name",goodsSize.getSizeName());
        return this.list(queryWrapper);
    }

    @Override
    public int insertGoodsSize(GoodsSize goodsSize) {
        return this.baseMapper.insert(goodsSize);
    }

    @Override
    public int updateGoodsSize(GoodsSize goodsSize) {
        return this.baseMapper.updateById(goodsSize);
    }

    @Override
    public int deleteGoodsSizeByIds(Integer[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteGoodsSizeById(Integer id) {
        return this.baseMapper.deleteById(id);
    }
}
