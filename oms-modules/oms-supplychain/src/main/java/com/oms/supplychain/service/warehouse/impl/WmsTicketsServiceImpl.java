package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.common.api.RemoteInventoryService;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.oms.supplychain.mapper.warehouse.WmsTicketsMapper;
import com.oms.supplychain.model.dto.warehouse.WmsTicketsDto;
import com.oms.supplychain.model.enmus.DocumentState;
import com.oms.supplychain.model.entity.Inventory.WmsInventoryBatch;
import com.oms.supplychain.model.entity.warehouse.SupplierInfo;
import com.oms.supplychain.model.entity.warehouse.WmsTickets;
import com.oms.supplychain.model.entity.warehouse.WmsTicketsGoods;
import com.oms.supplychain.service.warehouse.IWmsTicketsGoodsService;
import com.oms.supplychain.service.warehouse.IWmsTicketsService;
import com.ruoyi.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * 出入库单 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
@Slf4j
@Service
public class WmsTicketsServiceImpl extends ServiceImpl<WmsTicketsMapper, WmsTickets> implements IWmsTicketsService {

    @Resource
    private IWmsTicketsGoodsService wmsTicketsGoodsService;
    @Resource
    private RemoteInventoryService remoteInventoryService;

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

    @Override
    public boolean notice(String sn) {
        log.info("notice {}", sn);
        return false;
    }


    public boolean cGInventoryCallback(String sn) {
        this.wmsTicketsGoodsHandle(sn);
        WmsTicketsDto wmsTickets = this.baseMapper.getOneWithDetali(sn);
        if (ObjectUtil.isEmpty(wmsTickets)){
            return false;
        }
        List<WmsTicketsGoods> wmsTicketsGoodsList = wmsTickets.getWmsTicketsGoodsList();
        for (WmsTicketsGoods wmsTicketsGoods : wmsTicketsGoodsList) {
            WmsInventoryBatch inventoryBatch = new WmsInventoryBatch();
            inventoryBatch.setWmsSimulationCode(wmsTickets.getWmsSimulationCode());
            inventoryBatch.setSkuSn(wmsTicketsGoods.getSkuSn());

            inventoryBatch.setZpActualNumber(wmsTicketsGoods.getNumberZp());
            inventoryBatch.setZpAvailableNumber(wmsTicketsGoods.getNumberZp());
            inventoryBatch.setCpActualNumber(wmsTicketsGoods.getNumberCp());
            inventoryBatch.setCpAvailableNumber(wmsTicketsGoods.getNumberCp());
            inventoryBatch.setBrandCode(wmsTicketsGoods.getBrandCode());
            inventoryBatch.setRemark(wmsTickets.getRemark());
            inventoryBatch.setBatchCode(wmsTicketsGoods.getBatchCode());
            inventoryBatch.setTransactionPrice(wmsTicketsGoods.getPurchasePrice());
            R<Boolean> result = remoteInventoryService.addInventory(inventoryBatch, wmsTickets.getCompanyCode());
            if (!R.isSuccess(result)){
               log.info("库存处理失败");
                WmsTicketsGoods updateWmsTticetGoods = new WmsTicketsGoods();
                updateWmsTticetGoods.setId(wmsTicketsGoods.getId());
                updateWmsTticetGoods.setInventoryIsHandle(DocumentState.PROCESSED_FAIL.getCode());
                wmsTicketsGoodsService.updateById(wmsTicketsGoods);
            }else {
                WmsTicketsGoods updateWmsTticetGoods = new WmsTicketsGoods();
                updateWmsTticetGoods.setId(wmsTicketsGoods.getId());
                updateWmsTticetGoods.setInventoryIsHandle(DocumentState.PROCESSED_SUCCESS.getCode());
                wmsTicketsGoodsService.updateById(wmsTicketsGoods);
            }
        }
        WmsTickets tickets = new WmsTickets();
        tickets.setStatusTicket(DocumentState.STATUS_TICKET_PROCESSED.getCode());
        tickets.setStatusQuery(DocumentState.STATUS_QUERY_PROCESSED_SUCCESS.getCode());
        tickets.setStatusNotify(DocumentState.STATUS_NOTIFY_PROCESSED_SUCCESS.getCode());
        tickets.setAcceptCallbackTime(new Date());
        this.baseMapper.updateById(tickets);
        return true;
    }

    /**
     * 入库单处理
     * @param sn
     * @return
     */
    public Boolean wmsTicketsGoodsHandle(String sn)
    {
        WmsTicketsDto wmsTickets = this.baseMapper.getOneWithDetali(sn);
        if (ObjectUtil.isEmpty(wmsTickets)){
            return false;
        }
        List<WmsTicketsGoods> wmsTicketsGoodsList = wmsTickets.getWmsTicketsGoodsList();
        for (WmsTicketsGoods wmsTicketsGoods : wmsTicketsGoodsList) {
            WmsTicketsGoods ticketsGoods = new WmsTicketsGoods();
            ticketsGoods.setId(wmsTicketsGoods.getId());
            ticketsGoods.setNumberActually(wmsTicketsGoods.getNumberExpected());
            if (wmsTicketsGoods.getInventoryType() == DocumentState.ZP.getMsg()){
                ticketsGoods.setNumberZp(wmsTicketsGoods.getNumberExpected());
            }else {
                ticketsGoods.setNumberCp(wmsTicketsGoods.getNumberExpected());
            }
            wmsTicketsGoodsService.updateById(ticketsGoods);
        }
        return true;
    }
}
