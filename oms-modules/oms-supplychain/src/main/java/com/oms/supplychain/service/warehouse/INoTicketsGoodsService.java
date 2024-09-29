package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoods;

import java.util.List;

/**
 * <p>
 * 入库通知单明细 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-01
 */
public interface INoTicketsGoodsService extends IService<NoTicketsGoods> {
    public List<NoTicketsGoods> selectNoTicketsGoodsList(NoTicketsGoods noTicketsGoods);
}
