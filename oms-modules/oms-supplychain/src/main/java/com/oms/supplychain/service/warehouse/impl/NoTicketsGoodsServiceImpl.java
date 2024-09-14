package com.oms.supplychain.service.warehouse.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.NoTicketsGoodsMapper;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoods;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 入库通知单明细 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-01
 */
@Service
public class NoTicketsGoodsServiceImpl extends ServiceImpl<NoTicketsGoodsMapper, NoTicketsGoods> implements INoTicketsGoodsService {

}
