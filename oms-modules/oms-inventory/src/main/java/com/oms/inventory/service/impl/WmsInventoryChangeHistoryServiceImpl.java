package com.oms.inventory.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oms.inventory.mapper.WmsInventoryChangeHistoryMapper;
import com.oms.inventory.model.entity.WmsInventoryChangeHistory;
import com.oms.inventory.service.IWmsInventoryChangeHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * WMS库存变动历史记录 服务实现类
 *
 * @author AI Assistant
 * @since 2024-01-01
 */
@Slf4j
@Service
public class WmsInventoryChangeHistoryServiceImpl extends ServiceImpl<WmsInventoryChangeHistoryMapper, WmsInventoryChangeHistory> implements IWmsInventoryChangeHistoryService {

    @Override
    public boolean recordInventoryChange(WmsInventoryChangeHistory history) {
        try {
            // 设置操作时间和创建时间
            LocalDateTime now = LocalDateTime.now();
            if (history.getOperationTime() == null) {
                history.setOperationTime(now);
            }
            if (history.getCreateTime() == null) {
                history.setCreateTime(now);
            }
            if (history.getModifyTime() == null) {
                history.setModifyTime(now);
            }
            
            // 保存历史记录
            boolean result = this.save(history);
            
            if (result) {
                log.debug("WMS库存变动历史记录成功: {}", history);
            } else {
                log.error("WMS库存变动历史记录失败: {}", history);
            }
            
            return result;
        } catch (Exception e) {
            log.error("记录WMS库存变动历史时发生异常: {}", e.getMessage(), e);
            return false;
        }
    }

}