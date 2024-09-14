package com.oms.supplychain.service.warehouse.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.common.api.RemoteGoodsService;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.oms.supplychain.mapper.warehouse.NoTicketsGoodsTmpMapper;
import com.oms.supplychain.model.enmus.DocumentState;
import com.oms.supplychain.model.entity.warehouse.NoTicketExcel;
import com.oms.supplychain.model.entity.warehouse.NoTickets;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoods;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoodsTmp;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsService;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsTmpService;
import com.oms.supplychain.service.warehouse.INoTicketsService;
import com.ruoyi.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 入库通知单明细-未送审Service业务层处理
 *
 * @author ruoyi
 * @date 2024-09-03
 */
@Slf4j
@Service
public class NoTicketsGoodsTmpServiceImpl extends ServiceImpl<NoTicketsGoodsTmpMapper, NoTicketsGoodsTmp> implements INoTicketsGoodsTmpService
{

    @Resource
    private RemoteGoodsService remoteGoodsService;
    @Resource
    private INoTicketsService noTicketsService;
    @Resource
    private INoTicketsGoodsService noTicketsGoodsService;

    @Override
    public NoTicketsGoodsTmp selectNoTicketsGoodsTmpById(Long id) {
        return this.baseMapper.selectById(id);
    }

    @Override
    public List<NoTicketsGoodsTmp> selectNoTicketsGoodsTmpList(NoTicketsGoodsTmp noTicketsGoodsTmp) {
        QueryWrapper<NoTicketsGoodsTmp> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(noTicketsGoodsTmp.getNoSn())){
            queryWrapper.eq("no_sn",noTicketsGoodsTmp.getNoSn());
        }
        if (!StrUtil.isBlank(noTicketsGoodsTmp.getGoodsSn())){
            queryWrapper.eq("goods_sn",noTicketsGoodsTmp.getGoodsSn());
        }
        if (!StrUtil.isBlank(noTicketsGoodsTmp.getGoodsName())){
            queryWrapper.eq("goods_name",noTicketsGoodsTmp.getGoodsName());
        }
        if (!StrUtil.isBlank(noTicketsGoodsTmp.getBarcodeSn())){
            queryWrapper.eq("barcode_sn",noTicketsGoodsTmp.getBarcodeSn());
        }
        return this.list(queryWrapper);
    }

    @Override
    public int insertNoTicketsGoodsTmp(NoTicketsGoodsTmp noTicketsGoodsTmp) {
        log.debug("insertNoTicketsGoodsTmp"+noTicketsGoodsTmp);
        return this.baseMapper.insert(noTicketsGoodsTmp);
    }

    @Override
    @Transactional
    public boolean batchInsertNoTicketsGoodsTmp(List<NoTicketExcel> noTicketGoodsList,String noSn,String companyCode) {
        log.debug("noTicketsGoodsTmpList:"+noTicketGoodsList);
        QueryWrapper<NoTicketsGoodsTmp> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("no_sn", noSn);
        this.remove(queryWrapper);
        // 轮询 noTicketGoodsList
        List<NoTicketsGoodsTmp> tmpList = new ArrayList<>();
        for (NoTicketExcel item : noTicketGoodsList) {
            // 在这里处理每一个 item
            // 例如打印每个对象的信息
            log.debug("Processing item: " + item);
            NoTicketsGoodsTmp tmp = formatNoTicketsGoodsTmp(item, noSn,companyCode);
            log.debug("formatNoTicketsGoodsTmp item: " + tmp);
            tmpList.add(tmp);
        }
        return this.saveBatch(tmpList);
    }

    @Override
    public int updateNoTicketsGoodsTmp(NoTicketsGoodsTmp noTicketsGoodsTmp,String companyCode) {
        GoodsSkuSnInfo goodsSkuSnInfo = new GoodsSkuSnInfo();
        goodsSkuSnInfo.setSkuSn(noTicketsGoodsTmp.getSkuSn());
        String noSn = noTicketsGoodsTmp.getNoSn();
        NoTicketExcel noTicketExcel = new NoTicketExcel();
        noTicketExcel.setSkuSn(noTicketsGoodsTmp.getSkuSn());
        noTicketExcel.setPurchasePrice(noTicketsGoodsTmp.getPurchasePrice());
        noTicketExcel.setNumberExpected(noTicketsGoodsTmp.getZpNumberExpected());
        NoTicketsGoodsTmp tmp = formatNoTicketsGoodsTmp(noTicketExcel, noSn,companyCode);
        tmp.setId(noTicketsGoodsTmp.getId());
        return this.baseMapper.updateById(tmp);
    }

    @Override
    public int deleteNoTicketsGoodsTmpByIds(Long[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteNoTicketsGoodsTmpById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    @Override
    public boolean submitExamine(String noSn,String companyCode) {
        QueryWrapper<NoTicketsGoodsTmp> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("no_sn", noSn);
        queryWrapper.isNull("error_info");
        List<NoTicketsGoodsTmp> list = this.list(queryWrapper);
        if (ObjectUtil.isEmpty(list))
            return false;
        NoTickets noTickets = noTicketsService.getOne(new QueryWrapper<NoTickets>().eq("sn", noSn));
        if (ObjectUtil.isEmpty(noTickets))
            throw new RuntimeException("未找到入库单"+noSn+"信息");
        if (noTickets.getNoState() != DocumentState.CREATE.getCode()){
            throw new RuntimeException("入库单"+noSn+"状态不正确，无法提交审核");
        }
        int numberExpected = 0;
        BigDecimal priceExpected=new BigDecimal(0);
        List<NoTicketsGoods> ticketsGoodsList = new ArrayList<>();
        for (NoTicketsGoodsTmp tmp : list) {
            tmp.setCompanyCode(companyCode);
            NoTicketsGoods noTicketsGoods = formatNoTicketsGoods(tmp);
            ticketsGoodsList.add(noTicketsGoods);
            numberExpected = numberExpected + noTicketsGoods.getZpNumberExpected();
            priceExpected = priceExpected.add(noTicketsGoods.getPurchasePrice());
        }

        return saveNoTicketsGoods(ticketsGoodsList,numberExpected,priceExpected,noSn);
    }

    /**
     * 将临时商品信息对象转换为无票商品信息对象
     * 此方法用于格式化无票商品信息，将从临时表中获取的数据转换为商品信息表中的对象
     * 主要用于数据格式的转换，不涉及业务逻辑
     *
     * @param tmp 临时商品信息对象，包含了一系列商品相关信息
     * @return 返回转换后的无票商品信息对象
     */
    public NoTicketsGoods formatNoTicketsGoods(NoTicketsGoodsTmp tmp) {
        // 创建一个新的无票商品信息对象，用于存储格式化后的商品信息
        NoTicketsGoods ticketsGoods = new NoTicketsGoods();
        // 设置商品的无票单号
        ticketsGoods.setNoSn(tmp.getNoSn());
        // 设置商品的SKU编号
        ticketsGoods.setSkuSn(tmp.getSkuSn());
        // 设置商品的条形码编号
        ticketsGoods.setBarcodeSn(tmp.getBarcodeSn());
        // 设置商品的货品编号
        ticketsGoods.setGoodsSn(tmp.getGoodsSn());
        // 设置商品名称
        ticketsGoods.setGoodsName(tmp.getGoodsName());
        // 设置商品的采购价格
        ticketsGoods.setPurchasePrice(tmp.getPurchasePrice());
        // 设置预计的赠品数量
        ticketsGoods.setZpNumberExpected(tmp.getZpNumberExpected());
        // 设置公司代码
        ticketsGoods.setCompanyCode(tmp.getCompanyCode());
        // 返回填充了商品信息的无票商品信息对象
        return ticketsGoods;
    }


    /**
     * 保存提交审核信息并且更新入库通知单状态
     * @param ticketsGoodsList
     * @param numberExpected
     * @param priceExpected
     * @param noSn
     * @return
     */
    @Transactional
    public boolean saveNoTicketsGoods(List<NoTicketsGoods> ticketsGoodsList,int numberExpected,BigDecimal priceExpected,String noSn){
        noTicketsGoodsService.saveBatch(ticketsGoodsList);
        NoTickets noTickets = new NoTickets();
        noTickets.setNumberExpected(numberExpected);
        noTickets.setPriceExpected(priceExpected);
        noTickets.setNoState(DocumentState.AUDIT.getCode());
        UpdateWrapper<NoTickets> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("no_sn",noSn);
        return noTicketsService.updateNoTicketsByWrapper(noTickets,updateWrapper);
    }


    /**
     * 格式化导入信息
     * @param noTicketExcel
     * @param noSn
     * @param companyCode
     * @return
     */
    public NoTicketsGoodsTmp formatNoTicketsGoodsTmp(NoTicketExcel noTicketExcel, String noSn,String companyCode) {
        NoTicketsGoodsTmp tmp = new NoTicketsGoodsTmp();
        String skuSn = noTicketExcel.getSkuSn();
        BigDecimal purchasePrice = noTicketExcel.getPurchasePrice();
        int zpNumberExpected = noTicketExcel.getNumberExpected();
        GoodsSkuSnInfo goodsSkuSnInfo = new GoodsSkuSnInfo();
        goodsSkuSnInfo.setSkuSn(skuSn);
        R<GoodsSkuSnInfo> goodsSkuSnInfoR = remoteGoodsService.selectGoodsSkuSnInfo(goodsSkuSnInfo, companyCode);

        // 执行其他操作...
        if (!R.isSuccess(goodsSkuSnInfoR)){
            tmp.setErrorInfo("商品服务异常");
        }else {
            GoodsSkuSnInfo one = goodsSkuSnInfoR.getData();
            if (ObjectUtil.isEmpty(one)){
                tmp.setGoodsSn("");
                tmp.setBarcodeSn("");
                tmp.setGoodsSn("");
                tmp.setErrorInfo("导入商品不存在");
            }else {
                tmp.setGoodsSn(one.getGoodsSn());
                tmp.setBarcodeSn(one.getBarcodeSn());
                tmp.setGoodsName(one.getGoodsName());
                tmp.setErrorInfo("");
            }

        }
        tmp.setCompanyCode(companyCode);
        tmp.setNoSn(noSn);
        tmp.setSkuSn(skuSn);
        tmp.setPurchasePrice(purchasePrice);
        tmp.setZpNumberExpected(zpNumberExpected);
        return tmp;
    }
}

