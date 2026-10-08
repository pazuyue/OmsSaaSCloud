package com.oms.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.inventory.model.entity.WmsInventoryChangeHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * WMS 库存变动历史记录的数据访问接口。
 */
@Mapper
public interface WmsInventoryChangeHistoryMapper extends BaseMapper<WmsInventoryChangeHistory> {
}
