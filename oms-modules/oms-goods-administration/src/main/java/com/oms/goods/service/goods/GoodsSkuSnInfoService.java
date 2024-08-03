package com.oms.goods.service.goods;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;

import java.util.List;

/**
 * <p>
 * 产品信息表 服务类
 * </p>
 *
 * @author 月光
 * @since 2024-07-30
 */
public interface GoodsSkuSnInfoService extends IService<GoodsSkuSnInfo> {

    /**
     * 审核
     * @param importBatch
     * @return
     */
    public boolean toExamine(String importBatch,String companyCode);

    /**
     * 查询产品信息
     *
     * @param id 产品信息主键
     * @return 产品信息
     */
    public GoodsSkuSnInfo selectGoodsSkuSnInfoById(Long id);

    /**
     * 查询产品信息列表
     *
     * @param goodsSkuSnInfo 产品信息
     * @return 产品信息集合
     */
    public List<GoodsSkuSnInfo> selectGoodsSkuSnInfoList(GoodsSkuSnInfo goodsSkuSnInfo);

    /**
     * 新增产品信息
     *
     * @param goodsSkuSnInfo 产品信息
     * @return 结果
     */
    public int insertGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo);

    /**
     * 修改产品信息
     *
     * @param goodsSkuSnInfo 产品信息
     * @return 结果
     */
    public int updateGoodsSkuSnInfo(GoodsSkuSnInfo goodsSkuSnInfo);

    /**
     * 批量删除产品信息
     *
     * @param ids 需要删除的产品信息主键集合
     * @return 结果
     */
    public int deleteGoodsSkuSnInfoByIds(Long[] ids);

    /**
     * 删除产品信息信息
     *
     * @param id 产品信息主键
     * @return 结果
     */
    public int deleteGoodsSkuSnInfoById(Long id);

}
