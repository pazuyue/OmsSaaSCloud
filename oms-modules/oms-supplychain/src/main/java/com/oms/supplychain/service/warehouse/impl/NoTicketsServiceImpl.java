package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.NoTicketsMapper;
import com.oms.supplychain.model.enmus.DocumentState;
import com.oms.supplychain.model.entity.warehouse.NoTickets;
import com.oms.supplychain.model.entity.warehouse.OwnerInfo;
import com.oms.supplychain.service.warehouse.INoTicketsService;
import com.ruoyi.common.security.utils.SecurityUtils;
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
    public NoTickets getOne(QueryWrapper queryWrapper) {
        return this.baseMapper.selectOne(queryWrapper);
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
        String operName = SecurityUtils.getUsername();
        String noSn = "NO"+batchCode;
        noTickets.setNoSn(noSn);
        noTickets.setBatchCode(batchCode);
        noTickets.setCreatedUser(operName);
        return this.baseMapper.insert(noTickets);
    }

    @Override
    public int updateNoTickets(NoTickets noTickets) {
        return this.baseMapper.updateById(noTickets);
    }

    @Override
    public boolean updateNoTicketsByWrapper(NoTickets noTickets, UpdateWrapper updateWrapper) {
        return this.baseMapper.update(noTickets,updateWrapper) > 0;
    }

    @Override
    public int deleteNoTicketsByIds(Integer[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteNoTicketsById(Integer id) {
        return this.baseMapper.deleteById(id);
    }

    @Override
    public boolean examine(String noSn) {
        QueryWrapper<NoTickets> query = new QueryWrapper<>();
        query.eq("no_sn", noSn);
        NoTickets noTickets = this.getOne((Wrapper<NoTickets>) query);
        Integer state = noTickets.getNoState();
        if (state != DocumentState.AUDIT.getCode()) {
            throw new RuntimeException("采购入库单非待审核状态");
        }
        //createWarehousingNotificationOrder(noSn);
        return false;
    }
}
