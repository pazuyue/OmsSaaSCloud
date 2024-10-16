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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        if (!StrUtil.isBlank(noTickets.getNoSn())) {
            queryWrapper.eq("no_sn", noTickets.getNoSn());
        }
        if (!StrUtil.isBlank(noTickets.getPoSn())) {
            queryWrapper.eq("po_sn", noTickets.getPoSn());
        }
        if (!StrUtil.isBlank(noTickets.getNoName())) {
            queryWrapper.eq("no_name", noTickets.getNoName());
        }
        if (noTickets.getNoState() != null) {
            queryWrapper.eq("no_state", noTickets.getNoState());
        }
        return this.list(queryWrapper);
    }

    @Override
    public int insertNoTickets(NoTickets noTickets) {
        String batchCode = IdUtil.simpleUUID();
        String operName = SecurityUtils.getUsername();
        String noSn = "NO_" + batchCode;
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
        return this.baseMapper.update(noTickets, updateWrapper) > 0;
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
    /**
     * 审核采购入库单
     * 此方法主要用于审核采购入库单，根据入库单的状态进行不同的业务处理
     * 如果入库单状态为待审核，则创建采购入库通知单并根据实际情况进行库存回调
     * 根据库存回调结果更新采购入库单状态
     *
     * @param noSn 采购入库单号
     * @return 更新采购入库单操作的结果
     * @throws RuntimeException 如果采购入库单状态非待审核，抛出运行时异常
     */
    public boolean examine(String noSn) {
        // 根据采购入库单号查询采购入库单信息
        QueryWrapper<NoTickets> query = new QueryWrapper<>();
        query.eq("no_sn", noSn);
        NoTickets noTickets = getOne((Wrapper<NoTickets>) query);

        // 获取采购入库单的状态
        Integer state = noTickets.getNoState();

        // 检查采购入库单是否为待审核状态
        if (state != DocumentState.AUDIT.getCode()) {
            throw new RuntimeException("采购入库单非待审核状态");
        }

        // 创建采购入库通知单
        WmsTickets tickets = createWarehousingNotificationOrder(noTickets);
        // 如果采购入库通知单的实际入库状态为虚拟入库，则进行库存回调
        if (tickets.getActualWarehouse() == DocumentState.VIRTUALLY_WAREHOUSE.getCode()) {
            String sn = tickets.getSn();
            // 开始处理库存前，先执行WMS票证货物处理逻辑
            if (wmsTicketsService.wmsTicketsGoodsHandle(sn)) {
                boolean b = wmsTicketsService.cGInventoryCallback(sn);
                // 如果库存回调成功，则更新采购入库单状态为入库完成
                if (b) {
                    return this.end(noTickets,sn);
                }
            }
            return false;
        }

        // 如果不是虚拟入库或库存回调不成功，则更新采购入库单状态为待入库
        noTickets.setNoState(DocumentState.WAITWAREHOUSING.getCode());
        this.baseMapper.updateById(noTickets);
        return true;
    }


    /**
     * 完结票证
     *
     * 此方法用于更新票证和相关货物的状态，确保仓库中实际数量与票证相符
     * 它首先获取与票证关联的所有货物信息，然后根据仓库中的实际货物数量更新每个货物的记录
     * 最后，更新票证的状态为“入库完成”
     *
     * @param noTickets 票证对象，包含票证的详细信息
     * @param sn 票证编号，用于唯一标识一个票证
     * @return 返回更新操作的结果，通常表示成功与否
     */
    public boolean end(NoTickets noTickets, String sn) {
        // 根据票证编号查询仓库中对应的货物信息，并将其收集到一个Map中以便快速访问
        QueryWrapper<WmsTicketsGoods> wrapper = new QueryWrapper<>();
        wrapper.eq("sn", sn);
        Map<String, WmsTicketsGoods> WmsTicketsGoodsMap = wmsTicketsGoodsService.list(wrapper).stream().collect(Collectors.toMap(obj -> obj.getSkuSn() + "_" + obj.getInventoryType(), obj -> obj));
        log.info("WmsTicketsGoodsMap:{}", WmsTicketsGoodsMap);
        // 创建一个新的NoTicketsGoods对象并设置其票证编号，用于查询没有票证的货物列表
        NoTicketsGoods noTicketsGood = new NoTicketsGoods();
        noTicketsGood.setNoSn(noTickets.getNoSn());
        List<NoTicketsGoods> lists = noTicketsGoodsService.selectNoTicketsGoodsList(noTicketsGood);
        int allNumberActually = 0;
        BigDecimal AllPriceActually = new BigDecimal(0);
        // 遍历货物列表，根据仓库中的实际数量更新每个货物的记录
        for (NoTicketsGoods noTicketsGoods : lists) {
            if (WmsTicketsGoodsMap.containsKey(noTicketsGoods.getSkuSn() + "_ZP")) {
                WmsTicketsGoods wmsTicketsGoods = WmsTicketsGoodsMap.get(noTicketsGoods.getSkuSn() + "_ZP");
                noTicketsGoods.setZpNumberActually(wmsTicketsGoods.getNumberActually());
                allNumberActually = allNumberActually + wmsTicketsGoods.getNumberActually();
                AllPriceActually = AllPriceActually.add(wmsTicketsGoods.getPurchasePrice().multiply(new BigDecimal(wmsTicketsGoods.getNumberActually())));
            }
            if (WmsTicketsGoodsMap.containsKey(noTicketsGoods.getSkuSn() + "_CP")) {
                WmsTicketsGoods wmsTicketsGoods = WmsTicketsGoodsMap.get(noTicketsGoods.getSkuSn() + "_CP");
                noTicketsGoods.setCpNumberActually(wmsTicketsGoods.getNumberActually());
                allNumberActually = allNumberActually + wmsTicketsGoods.getNumberActually();
                AllPriceActually = AllPriceActually.add(wmsTicketsGoods.getPurchasePrice().multiply(new BigDecimal(wmsTicketsGoods.getNumberActually())));
            }
            noTicketsGoodsService.updateById(noTicketsGoods);
        }

        // 更新票证状态为“入库完成”
        noTickets.setNoState(DocumentState.WAREHOUSINGCOMPLETED.getCode());
        noTickets.setNumberActually(allNumberActually);
        noTickets.setPriceActually(AllPriceActually);
        this.baseMapper.updateById(noTickets);
        // 更新数据库中的票证信息
        return true;
    }



    /**
     * 创建入库通知单
     *
     * @param noTickets 采购单对象，包含采购信息和商品明细
     * @return 如果成功创建入库通知单，则返回true
     * <p>
     * 此方法负责根据采购单信息创建入库通知单，包括设置通知单编号、类型、关联采购单信息、
     * 虚仓信息、商品明细等，并最终保存到数据库中
     */
    private WmsTickets createWarehousingNotificationOrder(NoTickets noTickets) {
        // 开始创建入库通知单的日志记录
        log.info("开始创建入库通知单");

        // 生成入库通知单的唯一编号，前缀为"CG"
        String sn = "CG_" + IdUtil.simpleUUID();
        String operName = SecurityUtils.getUsername();
        // 根据采购单中的模拟仓库编码查询模拟仓库信息
        SimulationStoreInfoDto simulationStoreInfo = simulationStoreInfoMapper.selectSimulationStoreInfoWtihOwnerInfo(noTickets.getWmsSimulationCode());

        // 如果找不到指定编码的模拟仓库信息，抛出异常
        if (ObjectUtil.isEmpty(simulationStoreInfo))
            throw new RuntimeException("虚仓:" + noTickets.getWmsSimulationCode() + "信息不存在");
        QueryWrapper<WmsTickets> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("relation_sn", noTickets.getNoSn());
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
        tickets.setUserName(operName);
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
        return tickets;
    }

}
