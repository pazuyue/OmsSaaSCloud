package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.common.api.RemoteInventoryService;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.oms.common.model.entity.WmsInventoryBatch;
import com.oms.supplychain.mapper.warehouse.WmsTicketsMapper;
import com.oms.supplychain.model.dto.warehouse.WmsTicketsDto;
import com.oms.supplychain.model.enmus.DocumentState;
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
import java.util.Objects;

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


    /**
     * 库存回调处理方法
     * 该方法负责根据票号(sn)处理库存相关操作，包括查询票证详情、更新库存和状态等
     * @param sn 票证号，用于识别和查询特定的库存操作票证
     * @return boolean 表示库存处理是否成功，成功返回true，否则返回false
     */
    public boolean cGInventoryCallback(String sn) {
        // 查询指定票证号的详细信息
        WmsTicketsDto wmsTickets = this.baseMapper.getOneWithDetali(sn);
        if (ObjectUtil.isEmpty(wmsTickets)){
            // 如果未找到对应的票证信息，则处理失败，返回false
            return false;
        }

        // 获取票证关联的货物列表
        List<WmsTicketsGoods> wmsTicketsGoodsList = wmsTickets.getWmsTicketsGoodsList();
        for (WmsTicketsGoods wmsTicketsGoods : wmsTicketsGoodsList) {
            // 初始化库存批次对象，准备更新库存
            WmsInventoryBatch inventoryBatch = new WmsInventoryBatch();
            inventoryBatch.setStoreCode(wmsTickets.getWmsSimulationCode());
            inventoryBatch.setSkuSn(wmsTicketsGoods.getSkuSn());

            // 设置库存实际数量和可用数量
            inventoryBatch.setZpActualNumber(wmsTicketsGoods.getNumberZp());
            inventoryBatch.setZpAvailableNumber(wmsTicketsGoods.getNumberZp());
            inventoryBatch.setCpActualNumber(wmsTicketsGoods.getNumberCp());
            inventoryBatch.setCpAvailableNumber(wmsTicketsGoods.getNumberCp());

            // 设置品牌码、备注、批号和交易价格
            inventoryBatch.setBrandCode(wmsTicketsGoods.getBrandCode());
            inventoryBatch.setRemark(wmsTickets.getRemark());
            inventoryBatch.setBatchCode(wmsTicketsGoods.getBatchCode());
            inventoryBatch.setTransactionPrice(wmsTicketsGoods.getPurchasePrice());

            // 调用远程服务，执行库存更新操作
            R<Boolean> result = remoteInventoryService.addInventory(inventoryBatch, wmsTickets.getCompanyCode());
            log.info("库存处理结果：{}",result);
            if (!R.isSuccess(result)){
                // 如果库存更新失败，记录日志并更新货物状态为处理失败
                log.info("库存处理失败");
                WmsTicketsGoods updateWmsTticetGoods = new WmsTicketsGoods();
                updateWmsTticetGoods.setId(wmsTicketsGoods.getId());
                updateWmsTticetGoods.setInventoryIsHandle(DocumentState.PROCESSED_FAIL.getCode());
                log.info("库存处理失败，更新状态为：{}",updateWmsTticetGoods);
                wmsTicketsGoodsService.updateById(updateWmsTticetGoods);
            } else {
                // 如果库存更新成功，更新货物状态为处理成功
                WmsTicketsGoods updateWmsTticetGoods = new WmsTicketsGoods();
                updateWmsTticetGoods.setId(wmsTicketsGoods.getId());
                updateWmsTticetGoods.setInventoryIsHandle(DocumentState.PROCESSED_SUCCESS.getCode());
                wmsTicketsGoodsService.updateById(wmsTicketsGoods);
            }
        }

        // 更新票证状态，表示已处理完成
        WmsTickets tickets = new WmsTickets();
        Date date = new Date();
        tickets.setId(wmsTickets.getId());
        tickets.setStatusTicket(DocumentState.STATUS_TICKET_PROCESSED.getCode());
        tickets.setStatusQuery(DocumentState.STATUS_QUERY_PROCESSED_SUCCESS.getCode());
        tickets.setStatusNotify(DocumentState.STATUS_NOTIFY_PROCESSED_SUCCESS.getCode());
        tickets.setAcceptCallbackTime(date);
        tickets.setWmsActuallyTime(date);
        this.baseMapper.updateById(tickets);

        // 库存处理成功，返回true
        return true;
    }


    /**
     * 虚拟出入库单处理
     * @param sn
     * @return
     */
    public boolean wmsTicketsGoodsHandle(String sn)
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
            if (Objects.equals(wmsTicketsGoods.getInventoryType(), DocumentState.ZP.getMsg())){
                ticketsGoods.setNumberZp(wmsTicketsGoods.getNumberExpected());
            }else {
                ticketsGoods.setNumberCp(wmsTicketsGoods.getNumberExpected());
            }
            wmsTicketsGoodsService.updateById(ticketsGoods);
        }
        return true;
    }
}
