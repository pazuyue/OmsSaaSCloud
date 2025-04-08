package com.oms.inventory.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("oms_channel_inventory")
public class OmsChannelInventory {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer channelId;

    private String skuSn;

    private BigDecimal availableStock;

    private BigDecimal reservedStock;

    private BigDecimal frozenStock;

    private BigDecimal allocatedStock;

    private Date createTime;

    private Date modifyTime;

    private String companyCode;
    @Version
    private Integer version;
}
