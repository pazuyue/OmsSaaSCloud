package com.oms.goods.service.goods.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.oms.goods.mapper.GoodsCategoryMapper;
import com.oms.goods.model.entity.goods.GoodsCategory;
import com.oms.goods.service.goods.GoodsCategoryService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

@Service
public class GoodsCategoryServiceImpl implements GoodsCategoryService {

    @Resource
    private GoodsCategoryMapper goodsCategoryMapper;
    @Override
    public int save(GoodsCategory goodsCategory,String companyCode) {
        System.out.println("save"+goodsCategory);
        Object p = goodsCategory.getPid();
        if (!ObjectUtil.isEmpty(p)){
            int pid = (int) p;
            GoodsCategory goodsCategory1 = goodsCategoryMapper.selectById(pid);
            System.out.println("goodsCategory1"+goodsCategory1);
            if (ObjectUtil.isEmpty(goodsCategory1)){
                throw new RuntimeException("父ID不存在");
            }
            int level = goodsCategory1.getLevel() + 1;
            goodsCategory.setLevel((byte) level);
        }
        goodsCategory.setCompanyCode(companyCode);
        return goodsCategoryMapper.insert(goodsCategory);
    }

    @Override
    public int deleteById(int id) {
        return goodsCategoryMapper.deleteById(id);
    }

    @Override
    public int deleteGoodsCategoryByIds(Integer[] ids) {
        return goodsCategoryMapper.deleteBatchIds(Arrays.asList(ids));
    }

    public Integer selectCategoryCode(String categoryName)
    {
        QueryWrapper<GoodsCategory> queryWrapper = new QueryWrapper();
        queryWrapper.eq("name",categoryName).eq("level",3);
        GoodsCategory goodsCategory = goodsCategoryMapper.selectOne(queryWrapper);
        if (ObjectUtil.isEmpty(goodsCategory)){
            return 0;
        }
        return goodsCategoryMapper.selectOne(queryWrapper).getId();
    }

    @Override
    public GoodsCategory selectGoodsCategoryById(Integer id) {
        return goodsCategoryMapper.selectById(id);
    }


    /**
     * 查询商品类目列表
     *
     * @param goodsCategory 商品类目
     * @return 商品类目
     */
    @Override
    public List<GoodsCategory> selectGoodsCategoryList(GoodsCategory goodsCategory)
    {
        QueryWrapper<GoodsCategory> queryWrapper = new QueryWrapper();
        queryWrapper.eq(ObjectUtil.isNotEmpty(goodsCategory.getId()),"id",goodsCategory.getId());
        queryWrapper.eq(ObjectUtil.isNotEmpty(goodsCategory.getPid()),"pid",goodsCategory.getPid());
        queryWrapper.eq(ObjectUtil.isNotEmpty(goodsCategory.getCompanyCode()),"company_code",goodsCategory.getCompanyCode());
        return goodsCategoryMapper.selectList(queryWrapper);
    }

    @Override
    public int updateGoodsCategory(GoodsCategory goodsCategory) {
        return goodsCategoryMapper.updateById(goodsCategory);
    }
}