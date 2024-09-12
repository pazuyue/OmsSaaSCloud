package com.oms.supplychain.service.warehouse.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.common.api.RemoteGoodsService;
import com.oms.common.model.entity.GoodsSkuSnInfo;
import com.oms.supplychain.mapper.warehouse.NoTicketsGoodsTmpMapper;
import com.oms.supplychain.model.entity.warehouse.NoTicketExcel;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoodsTmp;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsTmpService;
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

