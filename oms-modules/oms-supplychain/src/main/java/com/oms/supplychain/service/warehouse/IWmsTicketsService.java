package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.WmsTickets;

/**
 * <p>
 * 出入库单 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
public interface IWmsTicketsService extends IService<WmsTickets> {

    public WmsTickets getOne(String sn);
}
