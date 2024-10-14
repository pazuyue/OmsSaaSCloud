package com.oms.supplychain.service.warehouse.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsTicketsGoodsMapper;
import com.oms.supplychain.model.entity.warehouse.WmsTicketsGoods;
import com.oms.supplychain.service.warehouse.IWmsTicketsGoodsService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 出入库明细表表 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
@Service
public class WmsTicketsGoodsServiceImpl extends ServiceImpl<WmsTicketsGoodsMapper, WmsTicketsGoods> implements IWmsTicketsGoodsService {

}
