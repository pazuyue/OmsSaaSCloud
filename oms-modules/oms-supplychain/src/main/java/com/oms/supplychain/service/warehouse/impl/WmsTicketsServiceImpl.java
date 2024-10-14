package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.WmsTicketsMapper;
import com.oms.supplychain.model.entity.warehouse.SupplierInfo;
import com.oms.supplychain.model.entity.warehouse.WmsTickets;
import com.oms.supplychain.service.warehouse.IWmsTicketsService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

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

    @Override
    public WmsTickets selectWmsTicketsById(Integer id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<WmsTickets> selectWmsTicketsList(WmsTickets wmsTickets) {
        QueryWrapper<WmsTickets> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsTickets.getSn()),"sn",wmsTickets.getSn());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsTickets.getRelationSn()),"relation_sn",wmsTickets.getRelationSn());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsTickets.getOriginalSn()),"original_sn",wmsTickets.getOriginalSn());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsTickets.getStatusTicket()),"status_ticket",wmsTickets.getStatusTicket());
        return this.list(queryWrapper);
    }

    @Override
    public int insertWmsTickets(WmsTickets wmsTickets) {

        return this.baseMapper.insert(wmsTickets);
    }

    @Override
    public int updateWmsTickets(WmsTickets wmsTickets) {
        return this.baseMapper.updateById(wmsTickets);
    }

    @Override
    public int deleteWmsTicketsByIds(Integer[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteWmsTicketsById(Integer id) {
        return this.baseMapper.deleteById(id);
    }
}
