package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.WmsSimulationStoreInfo;

/**
 * <p>
 * 虚仓表 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-02
 */
public interface WmsSimulationStoreInfoService extends IService<WmsSimulationStoreInfo> {

    public boolean save(WmsSimulationStoreInfo wmsSimulationStoreInfo,String companyCode);
}
