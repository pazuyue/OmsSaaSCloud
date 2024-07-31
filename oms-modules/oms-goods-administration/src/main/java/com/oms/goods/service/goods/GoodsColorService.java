package com.oms.goods.service.goods;

import com.oms.goods.model.entity.goods.GoodsColor;

import java.util.List;

public interface GoodsColorService {

    /**
     * 根据颜色名称返回颜色CODE，如果不存在，新增
     * @param color_name
     * @return
     */
    public Integer selectOrSaveByColorName(String color_name,String company_code);
    /**
     * 查询商品颜色
     *
     * @param id 商品颜色主键
     * @return 商品颜色
     */
    public GoodsColor selectGoodsColorById(Integer id);

    /**
     * 查询商品颜色列表
     *
     * @param goodsColor 商品颜色
     * @return 商品颜色集合
     */
    public List<GoodsColor> selectGoodsColorList(GoodsColor goodsColor);

    /**
     * 新增商品颜色
     *
     * @param goodsColor 商品颜色
     * @return 结果
     */
    public int insertGoodsColor(GoodsColor goodsColor);

    /**
     * 修改商品颜色
     *
     * @param goodsColor 商品颜色
     * @return 结果
     */
    public int updateGoodsColor(GoodsColor goodsColor);

    /**
     * 批量删除商品颜色
     *
     * @param ids 需要删除的商品颜色主键集合
     * @return 结果
     */
    public int deleteGoodsColorByIds(Integer[] ids);

    /**
     * 删除商品颜色信息
     *
     * @param id 商品颜色主键
     * @return 结果
     */
    public int deleteGoodsColorById(Integer id);
}
