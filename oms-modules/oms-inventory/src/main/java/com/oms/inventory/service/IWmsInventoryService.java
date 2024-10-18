package com.oms.inventory.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.entity.WmsInventory;

import java.util.List;

/**
 * <p>
 * 仓库库存表维度：sku_sn + store_code 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
public interface IWmsInventoryService extends IService<WmsInventory> {

    /**
     * 查询仓库库存
     *
     * @param id 仓库库存主键
     * @return 仓库库存
     */
    public WmsInventory selectWmsInventoryById(Long id);

    /**
     * 查询仓库库存列表
     *
     * @param wmsInventory 仓库库存
     * @return 仓库库存集合
     */
    public List<WmsInventory> selectWmsInventoryList(WmsInventory wmsInventory);

    /**
     * 新增仓库库存
     *
     * @param wmsInventory 仓库库存
     * @return 结果
     */
    public int insertWmsInventory(WmsInventory wmsInventory);

    /**
     * 修改仓库库存
     *
     * @param wmsInventory 仓库库存
     * @return 结果
     */
    public int updateWmsInventory(WmsInventory wmsInventory);

    /**
     * 批量删除仓库库存
     *
     * @param ids 需要删除的仓库库存主键集合
     * @return 结果
     */
    public int deleteWmsInventoryByIds(Long[] ids);

    /**
     * 删除仓库库存信息
     *
     * @param id 仓库库存主键
     * @return 结果
     */
    public int deleteWmsInventoryById(Long id);
}
