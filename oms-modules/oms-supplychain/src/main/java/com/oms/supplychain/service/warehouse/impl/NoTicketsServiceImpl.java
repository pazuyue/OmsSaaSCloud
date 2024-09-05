package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.NoTicketsMapper;
import com.oms.supplychain.model.entity.warehouse.NoTickets;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.service.warehouse.INoTicketsService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class NoTicketsServiceImpl extends ServiceImpl<NoTicketsMapper, NoTickets> implements INoTicketsService {
    @Override
    public NoTickets selectNoTicketsById(Integer id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<NoTickets> selectNoTicketsList(NoTickets noTickets) {
        QueryWrapper<NoTickets> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(noTickets.getNoSn())){
            queryWrapper.eq("no_sn",noTickets.getNoSn());
        }
        if (!StrUtil.isBlank(noTickets.getPoSn())){
            queryWrapper.eq("po_sn",noTickets.getPoSn());
        }
        if (!StrUtil.isBlank(noTickets.getNoName())){
            queryWrapper.eq("no_name",noTickets.getNoName());
        }
        if (noTickets.getNoState() != null){
            queryWrapper.eq("no_state",noTickets.getNoState());
        }
        return this.list(queryWrapper);
    }

    @Override
    public int insertNoTickets(NoTickets noTickets) {
        String batchCode = IdUtil.simpleUUID();
        noTickets.setBatchCode(batchCode);
        return this.baseMapper.insert(noTickets);
    }

    @Override
    public int updateNoTickets(NoTickets noTickets) {
        return this.baseMapper.updateById(noTickets);
    }

    @Override
    public int deleteNoTicketsByIds(Integer[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteNoTicketsById(Integer id) {
        return this.baseMapper.deleteById(id);
    }
}
