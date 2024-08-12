package com.oms.goods.service.goods;

import com.oms.goods.model.entity.goods.GoodsCategory;

import java.util.List;

public interface GoodsCategoryService {
    /**
     * 保存
     * @param goodsCategory
     * @return
     */
    public int save(GoodsCategory goodsCategory,String companyCode);

    /**
     * 删除
     * @param id
     * @return
     */
    public int deleteById(int id);

    /**
     * 根据类目名称返回类名CODE
     * @param categoryName
     * @return
     */
    public Integer selectCategoryCode(String categoryName);

    public List<GoodsCategory> selectGoodsCategoryList(GoodsCategory goodsCategory);
}
