package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.NoTicketsMapper;
import com.oms.supplychain.mapper.warehouse.WmsSimulationStoreInfoMapper;
import com.oms.supplychain.model.dto.warehouse.SimulationStoreInfoDto;
import com.oms.supplychain.model.enmus.DocumentState;
import com.oms.supplychain.model.entity.warehouse.*;
import com.oms.supplychain.service.warehouse.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class NoTicketsServiceImpl extends ServiceImpl<NoTicketsMapper, NoTickets> implements INoTicketsService {

    @Resource
    private INoTicketsGoodsService noTicketsGoodsService;
    @Resource
    private WmsSimulationStoreInfoMapper simulationStoreInfoMapper;
    @Resource
    private IWmsTicketsService wmsTicketsService;
    @Resource
    private IWmsTicketsGoodsService wmsTicketsGoodsService;

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
        NoTickets noTickets = getOne((Wrapper<NoTickets>) query);
        Integer state = noTickets.getNoState();
        if (state != DocumentState.AUDIT.getCode()) {
            throw new RuntimeException("采购入库单非待审核状态");
        }
        createWarehousingNotificationOrder(noTickets);
        return false;
    }

    /**
     * 创建入库通知单
     *
     * @param noTickets 采购单对象，包含采购信息和商品明细
     * @return 如果成功创建入库通知单，则返回true
     *
     * 此方法负责根据采购单信息创建入库通知单，包括设置通知单编号、类型、关联采购单信息、
     * 虚仓信息、商品明细等，并最终保存到数据库中
     */
    private boolean createWarehousingNotificationOrder(NoTickets noTickets) {
        // 开始创建入库通知单的日志记录
        log.info("开始创建入库通知单");

        // 生成入库通知单的唯一编号，前缀为"CG"
        String sn = "CG" + IdUtil.simpleUUID();

        // 根据采购单中的模拟仓库编码查询模拟仓库信息
        SimulationStoreInfoDto simulationStoreInfo = simulationStoreInfoMapper.selectSimulationStoreInfoWtihOwnerInfo(noTickets.getWmsSimulationCode());

        // 如果找不到指定编码的模拟仓库信息，抛出异常
        if (ObjectUtil.isEmpty(simulationStoreInfo))
            throw new RuntimeException("虚仓:" + noTickets.getWmsSimulationCode() + "信息不存在");
        QueryWrapper<WmsTickets> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("relation_sn",noTickets.getNoSn() );
        WmsTickets one = wmsTicketsService.getOne(queryWrapper);
        if (ObjectUtil.isNotEmpty(one))
            throw new RuntimeException("采购单:" + noTickets.getNoSn() + "已生成入库通知单");
        // 初始化入库通知单对象
        WmsTickets tickets = new WmsTickets();
        // 设置通知单编号
        tickets.setSn(sn);
        // 设置通知单类型为入库
        tickets.setTicketType(DocumentState.WAREHOUSING.getCode());
        // 设置关联的采购单编号
        tickets.setRelationSn(noTickets.getNoSn());
        // 设置原始单据编号（如采购订单编号）
        tickets.setOriginalSn(noTickets.getPoSn());
        // 设置模拟仓库编码和名称
        tickets.setWmsSimulationCode(simulationStoreInfo.getWmsSimulationCode());
        tickets.setWmsSimulationName(simulationStoreInfo.getWmsSimulationName());
        // 设置仓库类型为电商仓库
        tickets.setStoreType(DocumentState.E_COMMERCE_WAREHOUSE.getCode());
        // 设置备注信息
        tickets.setRemark(noTickets.getRemarks());
        // 设置公司编码
        tickets.setCompanyCode(noTickets.getCompanyCode());
        // 设置操作用户名
        tickets.setUserName(noTickets.getReviewerUser());
        // 设置实际仓库信息
        tickets.setActualWarehouse(simulationStoreInfo.getOwnerInfo().getRealStoreInfo().getActualWarehouse());

        // 初始化采购单商品对象，用于查询采购单商品明细
        NoTicketsGoods noTicketsGood = new NoTicketsGoods();
        noTicketsGood.setNoSn(noTickets.getNoSn());
        // 查询采购单的所有商品明细
        List<NoTicketsGoods> list = noTicketsGoodsService.selectNoTicketsGoodsList(noTicketsGood);
        // 初始化一个列表，用于存放入库通知单的商品明细
        ArrayList<WmsTicketsGoods> wmsTicketsGoodsArrayList = new ArrayList<>();
        for (NoTicketsGoods noTicketsGoods : list) {
            // 初始化入库通知单商品对象
            WmsTicketsGoods wmsTicketsGoods = new WmsTicketsGoods();
            // 设置通知单编号
            wmsTicketsGoods.setSn(sn);
            // 设置库存类型为正品
            wmsTicketsGoods.setInventoryType("ZP");
            // 设置商品SKU编号
            wmsTicketsGoods.setSkuSn(noTicketsGoods.getSkuSn());
            // 设置商品编号
            wmsTicketsGoods.setGoodsSn(noTicketsGoods.getGoodsSn());
            // 设置条形码编号
            wmsTicketsGoods.setBarcodeSn(noTicketsGoods.getBarcodeSn());
            // 设置商品名称
            wmsTicketsGoods.setGoodsName(noTicketsGoods.getGoodsName());
            // 设置批号
            wmsTicketsGoods.setBatchCode(noTickets.getBatchCode());
            // 设置采购价格
            wmsTicketsGoods.setPurchasePrice(noTicketsGoods.getPurchasePrice());
            // 设置预期入库数量
            wmsTicketsGoods.setNumberExpected(noTicketsGoods.getZpNumberExpected());
            // 设置公司编码
            wmsTicketsGoods.setCompanyCode(noTicketsGoods.getCompanyCode());
            // 将商品对象添加到列表中
            wmsTicketsGoodsArrayList.add(wmsTicketsGoods);
        }

        // 如果入库通知单商品列表为空，抛出异常
        if (wmsTicketsGoodsArrayList.size() == 0)
            throw new RuntimeException("入库通知单商品为空");

        // 保存入库通知单主单信息
        wmsTicketsService.save(tickets);
        // 批量保存入库通知单商品明细信息
        wmsTicketsGoodsService.saveBatch(wmsTicketsGoodsArrayList);
        // 返回创建成功标志
        return true;
    }

}
