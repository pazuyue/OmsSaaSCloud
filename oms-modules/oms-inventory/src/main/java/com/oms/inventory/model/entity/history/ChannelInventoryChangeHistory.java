package com.oms.inventory.model.entity.history;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
@TableName("channel_inventory_change_history") // 指定数据库表名
public class ChannelInventoryChangeHistory {

    // 主键
    @TableId(type = IdType.AUTO) // 自增主键
    private Long logId;

    // 操作时间（自动填充当前时间）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    // 操作类型（ALLOCATE/RELEASE/FROZEN/UNFROZEN/ADJUST/ORDER_DEDUCT/RETURN_RESTORE/MANUAL_ADJUST）
    private String operationType;

    // 渠道ID
    private Integer channelId;

    // 商品SKU
    private String skuSn;

    // 可售库存变更记录
    private Integer oldAvailableStock;
    private Integer newAvailableStock;

    // 预占库存变更记录
    private Integer oldReservedStock;
    private Integer newReservedStock;

    // 冻结库存变更记录
    private Integer oldFrozenStock;
    private Integer newFrozenStock;

    // 变动原因（冗余字段，可选项）
    private String changeReason;

    // 关联业务ID（订单ID、任务ID等）
    private String relatedId;

    // 关联业务类型（订单、任务等）
    private Integer relatedType;

    // 操作人
    private String operator;

    // 乐观锁版本号（与原表 oms_channel_inventory.version 对应）
    @Version
    private Integer version;

    // ========================
    // 以下为 Lombok 自动生成的构造方法、getter/setter 等
    // 使用 @Data 注解后无需手动编写
    // ========================
}
