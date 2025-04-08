package com.oms.inventory.service.impl;


import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.oms.inventory.mapper.WmsInventoryMapper;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.service.IWmsInventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 仓库库存表维度：sku_sn + store_code 服务实现类
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
@Slf4j
@Service
public class WmsInventoryServiceImpl extends ServiceImpl<WmsInventoryMapper, WmsInventory> implements IWmsInventoryService {

    @Override
    public WmsInventory selectWmsInventoryById(Long id) {
        return this.getById(id);
    }

    @Override
    public List<WmsInventory> selectWmsInventoryList(WmsInventory wmsInventory) {
        QueryWrapper<WmsInventory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsInventory.getSkuSn()),"sku_sn",wmsInventory.getSkuSn());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsInventory.getStoreCode()),"store_code",wmsInventory.getStoreCode());
        return this.list(queryWrapper);
    }

    //查询列表,Select sku_sn
    public List<WmsInventory> selectWmsInventoryListBySkuSn(WmsInventory wmsInventory) {
        QueryWrapper<WmsInventory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsInventory.getSkuSn()),"sku_sn",wmsInventory.getSkuSn());
        queryWrapper.select("sku_sn");
        return this.list(queryWrapper);
    }

    @Override
    public int insertWmsInventory(WmsInventory wmsInventory) {
        return 0;
    }

    @Override
    public int updateWmsInventory(WmsInventory wmsInventory) {
        return this.baseMapper.updateById(wmsInventory);
    }

    @Override
    public int deleteWmsInventoryByIds(Long[] ids) {
        return 0;
    }

    @Override
    public int deleteWmsInventoryById(Long id) {
        return 0;
    }

    @Override
    public List<Map<String, Object>> selectSkuListTotalAvailable(List<String> storeCodes, List<String> skuList, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return this.baseMapper.selectSkuListTotalAvailable(storeCodes,skuList);
    }

    @Override
    public Map<String, Object> selectSkuTotalAvailable(List<String> storeCodes, String skuSn) {
        return this.baseMapper.selectSkuTotalAvailable(storeCodes,skuSn);
    }


}
