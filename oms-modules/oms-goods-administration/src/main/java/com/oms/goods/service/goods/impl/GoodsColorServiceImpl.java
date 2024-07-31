package com.oms.goods.service.goods.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.goods.mapper.GoodsColorMapper;
import com.oms.goods.mapper.GoodsSizeMapper;
import com.oms.goods.model.entity.goods.GoodsColor;
import com.oms.goods.model.entity.goods.GoodsSize;
import com.oms.goods.service.goods.GoodsColorService;
import com.ruoyi.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;


@Service
public class GoodsColorServiceImpl extends ServiceImpl<GoodsColorMapper, GoodsColor> implements GoodsColorService {


    @Override
    public Integer selectOrSaveByColorName(String color_name,String company_code) {
        QueryWrapper<GoodsColor> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("color_name",color_name);
        GoodsColor goodsColor = this.baseMapper.selectOne(queryWrapper);
        if (ObjectUtil.isEmpty(goodsColor)){
            GoodsColor color = new GoodsColor();
            color.setColorName("color_name");
            color.setCompanyCode(company_code);
            return this.baseMapper.insert(color);
        }

        return goodsColor.getId();
    }

    @Override
    public GoodsColor selectGoodsColorById(Integer id) {
        return this.getById(id);
    }

    @Override
    public List<GoodsColor> selectGoodsColorList(GoodsColor goodsColor) {
        QueryWrapper<GoodsColor> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotEmpty(goodsColor.getCompanyCode()),"company_code", goodsColor.getCompanyCode());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsColor.getColorName()),"color_name",goodsColor.getColorName());
        queryWrapper.eq(StringUtils.isNotEmpty(goodsColor.getOutColorCode()),"out_color_code",goodsColor.getOutColorCode());
        return this.list(queryWrapper);
    }

    @Override
    public int insertGoodsColor(GoodsColor goodsColor) {
        return this.baseMapper.insert(goodsColor);
    }

    @Override
    public int updateGoodsColor(GoodsColor goodsColor) {
        return this.baseMapper.updateById(goodsColor);
    }

    @Override
    public int deleteGoodsColorByIds(Integer[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteGoodsColorById(Integer id) {
        return this.baseMapper.deleteById(id);
    }
}
