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
     * 批量删除商品类目
     *
     * @param ids 需要删除的商品类目主键集合
     * @return 结果
     */
    public int deleteGoodsCategoryByIds(Integer[] ids);

    /**
     * 根据类目名称返回类名CODE
     * @param categoryName
     * @return
     */
    public Integer selectCategoryCode(String categoryName);

    public GoodsCategory selectGoodsCategoryById(Integer id);

    public List<GoodsCategory> selectGoodsCategoryList(GoodsCategory goodsCategory);

    /**
     * 修改商品类目
     *
     * @param goodsCategory 商品类目
     * @return 结果
     */
    public int updateGoodsCategory(GoodsCategory goodsCategory);
}
