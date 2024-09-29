package com.oms.supplychain.service.warehouse.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.NoTicketsGoodsMapper;
import com.oms.supplychain.model.entity.warehouse.NoTickets;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoods;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 入库通知单明细 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-01
 */
@Service
public class NoTicketsGoodsServiceImpl extends ServiceImpl<NoTicketsGoodsMapper, NoTicketsGoods> implements INoTicketsGoodsService {

    @Override
    public List<NoTicketsGoods> selectNoTicketsGoodsList(NoTicketsGoods noTicketsGoods) {
        QueryWrapper<NoTicketsGoods> queryWrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(noTicketsGoods.getNoSn())){
            queryWrapper.eq("no_sn",noTicketsGoods.getNoSn());
        }
        if (!StrUtil.isBlank(noTicketsGoods.getSkuSn())){
            queryWrapper.eq("sku_sn",noTicketsGoods.getSkuSn());
        }
        if (!StrUtil.isBlank(noTicketsGoods.getBarcodeSn())){
            queryWrapper.eq("barcode_sn",noTicketsGoods.getBarcodeSn());
        }
        if (!StrUtil.isBlank(noTicketsGoods.getGoodsSn())){
            queryWrapper.eq("goods_sn",noTicketsGoods.getGoodsSn());
        }
        return this.list(queryWrapper);
    }
}
