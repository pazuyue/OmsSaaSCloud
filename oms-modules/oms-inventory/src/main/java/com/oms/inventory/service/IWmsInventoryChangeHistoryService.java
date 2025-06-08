package com.oms.inventory.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.entity.WmsInventoryChangeHistory;

/**
 * WMS库存变动历史记录 服务类
 *
 * @author AI Assistant
 * @since 2024-01-01
 */
public interface IWmsInventoryChangeHistoryService extends IService<WmsInventoryChangeHistory> {

    /**
     * 记录WMS库存变动历史
     *
     * @param history 库存变动历史记录
     * @return 是否记录成功
     */
    boolean recordInventoryChange(WmsInventoryChangeHistory history);

}