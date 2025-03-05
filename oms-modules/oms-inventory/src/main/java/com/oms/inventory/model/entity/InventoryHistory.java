package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InventoryHistory {

    @TableId(type = IdType.AUTO)
    private Long id; // 日志唯一ID

    private String skuSn;                // SKU

    private String operationType;      // 操作类型（INCREASE/DECREASE/ALLOCATE/FREEZE/RESERVE）
    private Long operatorId;          // 操作人ID
    private String operatorName;      // 操作人名称

    private Integer beforeAvailable;   // 变更前可售库存
    private Integer afterAvailable;    // 变更后可售库存
    private Integer beforeReserved;    // 变更前预留库存
    private Integer afterReserved;    // 变更后预留库存
    private Integer beforeFrozen;     // 变更前冻结库存
    private Integer afterFrozen;     // 变更后冻结库存
    private Integer beforeMinStock;  // 变更前最小库存预警值
    private Integer afterMinStock;  // 变更后最小库存预警值
    private Integer changeAmount;     // 总变动量（例如：DECREASE时减少量为正数）
    private LocalDateTime changeTime;  // 变更时间
}
