package com.oms.supplychain.service.warehouse.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsTicketsMapper;
import com.oms.supplychain.model.entity.warehouse.WmsTickets;
import com.oms.supplychain.service.warehouse.IWmsTicketsService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 出入库单 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
@Service
public class WmsTicketsServiceImpl extends ServiceImpl<WmsTicketsMapper, WmsTickets> implements IWmsTicketsService {

    public WmsTickets getOne(String sn){
        QueryWrapper<WmsTickets> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("sn",sn);
        return this.getOne(queryWrapper);
    }
}
