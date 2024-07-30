package com.oms.goods.service.goods.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.oms.goods.mapper.GoodsColorMapper;
import com.oms.goods.model.entity.goods.GoodsColor;
import com.oms.goods.service.goods.GoodsColorService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class GoodsColorServiceImpl implements GoodsColorService {

    @Resource
    private GoodsColorMapper goodsColorMapper;

    @Override
    public Integer selectOrSaveByColorName(String color_name,String company_code) {
        QueryWrapper<GoodsColor> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("color_name",color_name);
        GoodsColor goodsColor = goodsColorMapper.selectOne(queryWrapper);
        if (ObjectUtil.isEmpty(goodsColor)){
            GoodsColor color = new GoodsColor();
            color.setColorName("color_name");
            color.setCompanyCode(company_code);
            return goodsColorMapper.insert(color);
        }

        return goodsColor.getId();
    }
}
