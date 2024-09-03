package com.oms.supplychain.service.warehouse.impl;

import java.util.Arrays;
import java.util.List;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.supplychain.mapper.warehouse.NoTicketsGoodsTmpMapper;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoodsTmp;
import com.oms.supplychain.service.warehouse.INoTicketsGoodsTmpService;
import lombok.extern.slf4j.Slf4j;
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
    public boolean batchInsertNoTicketsGoodsTmp(List<NoTicketsGoodsTmp> noTicketsGoodsTmpList) {
        log.debug("noTicketsGoodsTmpList:"+noTicketsGoodsTmpList);
        return this.saveBatch(noTicketsGoodsTmpList);
    }

    @Override
    public int updateNoTicketsGoodsTmp(NoTicketsGoodsTmp noTicketsGoodsTmp) {
        return this.baseMapper.updateById(noTicketsGoodsTmp);
    }

    @Override
    public int deleteNoTicketsGoodsTmpByIds(Long[] ids) {
        return this.baseMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public int deleteNoTicketsGoodsTmpById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}

