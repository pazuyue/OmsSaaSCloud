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
        try {
            // 获取更新前的库存信息（包含当前版本号）
            WmsInventory oldInventory = this.getById(wmsInventory.getId());
            if (oldInventory == null) {
                throw new RuntimeException("库存记录不存在，ID: " + wmsInventory.getId());
            }

            // 设置版本号用于乐观锁控制
            if (wmsInventory.getVersion() == null) {
                wmsInventory.setVersion(oldInventory.getVersion());
            }

            // 执行库存更新（MyBatis-Plus会自动处理乐观锁）
            int result = this.baseMapper.updateById(wmsInventory);

            if (result == 0) {
                // 更新失败，可能是版本号冲突（并发更新）
                throw new RuntimeException("库存更新失败，可能存在并发更新冲突，请重试");
            }
            // 记录库存变化历史
            recordInventoryUpdateHistory(oldInventory, wmsInventory);
            return result;
        } catch (Exception e) {
            log.error("更新WMS库存失败，库存ID: {}, 版本号: {}", wmsInventory.getId(), wmsInventory.getVersion(), e);
            throw new RuntimeException("更新WMS库存失败: " + e.getMessage(), e);
        }
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
    public Boolean lockInventory(List<String> storeCodes, String sku, BigDecimal quantity) {
        try {
            // 调用Mapper方法锁定wms_inventory库存
            int result = this.baseMapper.lockInventory(storeCodes, sku, quantity);

            // 同时锁定wms_inventory_batch库存
            int batchResult = wmsInventoryBatchMapper.lockInventory(storeCodes, sku, quantity);

            log.debug("锁库操作结果 - wms_inventory: {}, wms_inventory_batch: {}", result, batchResult);

            if (result > 0) {
                // 记录WMS库存变动历史
                recordWmsInventoryChangeHistory(storeCodes, sku, quantity, "LOCK", "锁库分货", null);
            }

            return result > 0;
        } catch (Exception e) {
            log.error("锁定库存失败，storeCodes: {}, sku: {}, quantity: {}", storeCodes, sku, quantity, e);
            return false;
        }
    }

    @Override
    public Boolean unlockInventory(List<String> storeCodes, String sku, BigDecimal quantity) {
        try {
            // 调用Mapper方法解锁wms_inventory库存
            int result = this.baseMapper.unlockInventory(storeCodes, sku, quantity);

            // 同时解锁wms_inventory_batch库存
            int batchResult = wmsInventoryBatchMapper.unlockInventory(storeCodes, sku, quantity);

            log.debug("解锁操作结果 - wms_inventory: {}, wms_inventory_batch: {}", result, batchResult);

            if (result > 0) {
                // 记录WMS库存变动历史
                recordWmsInventoryChangeHistory(storeCodes, sku, quantity, "UNLOCK", "解锁库存", null);
            }

            return result > 0;
        } catch (Exception e) {
            log.error("解锁库存失败，storeCodes: {}, sku: {}, quantity: {}", storeCodes, sku, quantity, e);
            return false;
        }
    }


    /**
     * 记录库存更新历史
     *
     * @param oldInventory 更新前的库存信息
     * @param newInventory 更新后的库存信息
     */
    private void recordInventoryUpdateHistory(WmsInventory oldInventory, WmsInventory newInventory) {
        try {
            WmsInventoryChangeHistory history = new WmsInventoryChangeHistory();
            history.setOperationType("UPDATE");
            history.setStoreCode(newInventory.getStoreCode());
            history.setSkuSn(newInventory.getSkuSn());
            history.setChangeReason("库存更新");
            history.setOperationTime(LocalDateTime.now());
            history.setCompanyCode(newInventory.getCompanyCode());

            // 设置更新前的库存信息
            history.setOldZpActualNumber(oldInventory.getZpActualNumber());
            history.setOldZpAvailableNumber(oldInventory.getZpAvailableNumber());
            history.setOldZpLockNumber(oldInventory.getZpLockNumber());
            history.setOldCpActualNumber(oldInventory.getCpActualNumber());
            history.setOldCpAvailableNumber(oldInventory.getCpAvailableNumber());
            history.setOldCpLockNumber(oldInventory.getCpLockNumber());

            // 设置更新后的库存信息
            history.setNewZpActualNumber(newInventory.getZpActualNumber());
            history.setNewZpAvailableNumber(newInventory.getZpAvailableNumber());
            history.setNewZpLockNumber(newInventory.getZpLockNumber());
            history.setNewCpActualNumber(newInventory.getCpActualNumber());
            history.setNewCpAvailableNumber(newInventory.getCpAvailableNumber());
            history.setNewCpLockNumber(newInventory.getCpLockNumber());

            // 计算变化数量（以正品可用库存为例）
            BigDecimal changeQuantity = BigDecimal.valueOf(
                newInventory.getZpAvailableNumber() - oldInventory.getZpAvailableNumber()
            );
            history.setChangeQuantity(changeQuantity);

            // 记录历史
            wmsInventoryChangeHistoryService.recordInventoryChange(history);

        } catch (Exception e) {
            log.error("记录库存更新历史失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 记录WMS库存变动历史
     *
     * @param storeCodes 仓库编码列表
     * @param sku SKU编号
     * @param quantity 变动数量
     * @param operationType 操作类型
     * @param changeReason 变动原因
     * @param relationSn 关联单号
     */
    private void recordWmsInventoryChangeHistory(List<String> storeCodes, String sku, BigDecimal quantity,
                                                String operationType, String changeReason, String relationSn) {
        try {
            for (String storeCode : storeCodes) {
                // 查询当前库存信息
                QueryWrapper<WmsInventory> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("store_code", storeCode);
                queryWrapper.eq("sku_sn", sku);
                WmsInventory currentInventory = this.getOne(queryWrapper);

                if (currentInventory != null) {
                    WmsInventoryChangeHistory history = new WmsInventoryChangeHistory();
                    history.setOperationType(operationType);
                    history.setStoreCode(storeCode);
                    history.setSkuSn(sku);
                    history.setChangeQuantity(quantity);
                    history.setChangeReason(changeReason);
                    history.setRelationSn(relationSn);
                    history.setOperationTime(LocalDateTime.now());
                    history.setCompanyCode(currentInventory.getCompanyCode());

                    // 设置变更后的库存信息
                    history.setNewZpActualNumber(currentInventory.getZpActualNumber());
                    history.setNewZpAvailableNumber(currentInventory.getZpAvailableNumber());
                    history.setNewZpLockNumber(currentInventory.getZpLockNumber());
                    history.setNewCpActualNumber(currentInventory.getCpActualNumber());
                    history.setNewCpAvailableNumber(currentInventory.getCpAvailableNumber());
                    history.setNewCpLockNumber(currentInventory.getCpLockNumber());

                    // 计算变更前的库存信息
                    if ("LOCK".equals(operationType)) {
                        // 锁定操作：变更前锁定数量更少，可用数量更多
                        history.setOldZpLockNumber(currentInventory.getZpLockNumber() - quantity.intValue());
                        history.setOldZpAvailableNumber(currentInventory.getZpAvailableNumber() + quantity.intValue());
                    } else if ("UNLOCK".equals(operationType)) {
                        // 解锁操作：变更前锁定数量更多，可用数量更少
                        history.setOldZpLockNumber(currentInventory.getZpLockNumber() + quantity.intValue());
                        history.setOldZpAvailableNumber(currentInventory.getZpAvailableNumber() - quantity.intValue());
                    }

                    history.setOldZpActualNumber(currentInventory.getZpActualNumber());
                    history.setOldCpActualNumber(currentInventory.getCpActualNumber());
                    history.setOldCpAvailableNumber(currentInventory.getCpAvailableNumber());
                    history.setOldCpLockNumber(currentInventory.getCpLockNumber());

                    // 记录历史
                    wmsInventoryChangeHistoryService.recordInventoryChange(history);
                }
            }
        } catch (Exception e) {
            log.error("记录WMS库存变动历史失败: {}", e.getMessage(), e);
        }
    }

}
