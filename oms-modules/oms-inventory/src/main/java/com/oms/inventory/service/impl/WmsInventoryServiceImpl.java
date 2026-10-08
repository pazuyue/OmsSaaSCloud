package com.oms.inventory.service.impl;


import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.oms.inventory.mapper.WmsInventoryMapper;
import com.oms.inventory.mapper.WmsInventoryBatchMapper;
import com.oms.inventory.model.entity.WmsInventory;
import com.oms.inventory.model.entity.WmsInventoryChangeHistory;
import com.oms.inventory.service.IWmsInventoryService;
import com.oms.inventory.service.IWmsInventoryChangeHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Resource
    private InventoryMutationService mutations;

    @Resource
    private IWmsInventoryChangeHistoryService wmsInventoryChangeHistoryService;

    @Resource
    private WmsInventoryBatchMapper wmsInventoryBatchMapper;

    @Override
    public WmsInventory selectWmsInventoryById(Long id) {
        return this.getById(id);
    }

    @Override
    public List<WmsInventory> selectWmsInventoryList(WmsInventory wmsInventory) {
        QueryWrapper<WmsInventory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("company_code", com.oms.inventory.service.InventoryCompany.current());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsInventory.getSkuSn()),"sku_sn",wmsInventory.getSkuSn());
        queryWrapper.eq(ObjectUtil.isNotEmpty(wmsInventory.getStoreCode()),"store_code",wmsInventory.getStoreCode());
        return this.list(queryWrapper);
    }

    //查询列表,Select sku_sn
    public List<WmsInventory> selectWmsInventoryListBySkuSn(WmsInventory wmsInventory) {
        QueryWrapper<WmsInventory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("company_code", com.oms.inventory.service.InventoryCompany.current());
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
        throw new IllegalArgumentException("请在批次详情中通过库存调整操作修改库存");
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

    @Override
    public Boolean lockInventory(String company, List<String> storeCodes, String sku, BigDecimal quantity, String relationSn) {
        return mutations.reserve(company, storeCodes, sku, quantity, relationSn, false);
    }

    @Override
    public Boolean unlockInventory(String company, List<String> storeCodes, String sku, BigDecimal quantity, String relationSn) {
        return mutations.reserve(company, storeCodes, sku, quantity, relationSn, true);
    }


}
