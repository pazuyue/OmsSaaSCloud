package com.oms.goods.service.goods;

import com.oms.goods.model.entity.goods.GoodsSize;

import java.util.List;

public interface GoodsSizeService {

    /**
     * 根据尺码名称返回尺码CODE，如果不存在，新增
     * @param size_name
     * @return
     */
    public Integer selectOrSaveBySizeName(String size_name,String company_code);

    /**
     * 查询商品尺码
     *
     * @param id 商品尺码主键
     * @return 商品尺码
     */
    public GoodsSize selectGoodsSizeById(Integer id);

    /**
     * 查询商品尺码列表
     *
     * @param goodsSize 商品尺码
     * @return 商品尺码集合
     */
    public List<GoodsSize> selectGoodsSizeList(GoodsSize goodsSize);

    /**
     * 新增商品尺码
     *
     * @param goodsSize 商品尺码
     * @return 结果
     */
    public int insertGoodsSize(GoodsSize goodsSize);

    /**
     * 修改商品尺码
     *
     * @param goodsSize 商品尺码
     * @return 结果
     */
    public int updateGoodsSize(GoodsSize goodsSize);

    /**
     * 批量删除商品尺码
     *
     * @param ids 需要删除的商品尺码主键集合
     * @return 结果
     */
    public int deleteGoodsSizeByIds(Integer[] ids);

    /**
     * 删除商品尺码信息
     *
     * @param id 商品尺码主键
     * @return 结果
     */
    public int deleteGoodsSizeById(Integer id);
}
