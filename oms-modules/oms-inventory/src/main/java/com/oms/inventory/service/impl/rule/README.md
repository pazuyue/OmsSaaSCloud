##数据库表结构

## 数据库表结构

CREATE TABLE `wms_inventory` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `store_code` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库编码',
  `sku_sn` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU编码',
  `zp_actual_number` int(11) NOT NULL DEFAULT '0' COMMENT '正品实际库存：实际库存发货时减少',
  `cp_actual_number` int(11) NOT NULL DEFAULT '0' COMMENT '次品实际库存：实际库存发货时减少',
  `zp_available_number` int(11) NOT NULL DEFAULT '0' COMMENT '可用正品库存',
  `cp_available_number` int(11) NOT NULL DEFAULT '0' COMMENT '可用次品库存',
  `zp_lock_number` int(11) NOT NULL DEFAULT '0' COMMENT '正品预占库存：总正品预占库存,（退仓，B2B出库，其他出库，调拨出库,B2C订单，锁库单），包装套装预占',
  `cp_lock_number` int(11) NOT NULL DEFAULT '0' COMMENT '次品预占库存',
  `remark` text COLLATE utf8mb4_bin COMMENT '备注',
  `modify_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `company_code` varchar(255) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '公司编码',
  `version` int(11) DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `sku_sn_index` (`sku_sn`,`store_code`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='库存信息表';

CREATE TABLE `oms_channel_inventory` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '渠道库存唯一标识',
  `channel_id` int(11) NOT NULL COMMENT '渠道ID',
  `sku_sn` varchar(50) NOT NULL COMMENT '商品SKU',
  `available_stock` int(11) NOT NULL DEFAULT '0' COMMENT '可售库存（该渠道实际可销售数量）',
  `reserved_stock` int(11) NOT NULL DEFAULT '0' COMMENT '预占库存（订单占用）',
  `frozen_stock` int(11) NOT NULL DEFAULT '0' COMMENT '冻结库存（用户退货中/违规下架）',
  `allocated_stock` int(11) NOT NULL DEFAULT '0' COMMENT '已分配库存（锁库单）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '数据创建时间',
  `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '数据更新时间',
  `company_code` varchar(50) NOT NULL COMMENT '公司编码',
  `version` int(11) NOT NULL DEFAULT '1' COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_channel_sku` (`channel_id`,`sku_sn`),
  KEY `idx_channel` (`channel_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COMMENT='渠道库存表';

CREATE TABLE `channel_inventory_change_history` (
  `log_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '日志唯一标识',
  `operation_type` varchar(20) DEFAULT NULL COMMENT '操作类型（ALLOCATE/RELEASE/FROZEN/UNFROZEN/ADJUST/OTHER）',
  `channel_id` int(11) DEFAULT NULL COMMENT '渠道ID',
  `sku_sn` varchar(50) NOT NULL DEFAULT '' COMMENT '商品SKU',
  `old_available_stock` int(11) DEFAULT NULL COMMENT '变更前可售库存',
  `new_available_stock` int(11) DEFAULT NULL COMMENT '变更后可售库存',
  `old_reserved_stock` int(11) DEFAULT NULL COMMENT '变更前预占库存',
  `new_reserved_stock` int(11) DEFAULT NULL COMMENT '变更后预占库存',
  `old_frozen_stock` int(11) DEFAULT NULL COMMENT '变更前冻结库存',
  `new_frozen_stock` int(11) DEFAULT NULL COMMENT '变更后冻结库存',
  `old_allocated_stock` int(11) DEFAULT NULL COMMENT '变更前已分配库存',
  `new_allocated_stock` int(11) DEFAULT NULL COMMENT '变更后已分配库存',
  `change_reason` varchar(50) DEFAULT NULL COMMENT '变动原因（如订单扣减、退货恢复）',
  `related_id` varchar(50) DEFAULT NULL COMMENT '关联业务ID（如分货单ID、调整任务ID）',
  `related_type` tinyint(4) NOT NULL DEFAULT '0' COMMENT '关联业务类型 0 未知 1分货单',
  `operator` varchar(50) DEFAULT NULL COMMENT '操作人/系统',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `version` int(11) DEFAULT NULL,
  PRIMARY KEY (`log_id`) USING BTREE,
  KEY `idx_channel_sku` (`channel_id`,`sku_sn`),
  KEY `idx_operation_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COMMENT='库存变动全字段日志表';

CREATE TABLE `rule_stock_store_code_info` (
  `id` int(11) unsigned NOT NULL AUTO_INCREMENT,
  `rule_id` int(11) NOT NULL COMMENT '规则ID rule_stock_info.id',
  `store_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '虚仓编码wms_simulation_store_info.wms_simulation_code',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司编码',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='分货单-仓库分货信息';

CREATE TABLE `rule_stock_channel_info` (
  `id` int(11) unsigned NOT NULL AUTO_INCREMENT,
  `rule_id` int(11) NOT NULL COMMENT '规则ID rule_stock_info.id',
  `channel_id` int(11) NOT NULL COMMENT '店铺ID',
  `channel_name` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '店铺名称',
  `percentage` decimal(11,2) unsigned NOT NULL COMMENT '分配值',
  `rule_type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '分货类型 1 百分百 2整型数量',
  `decimal_handle_type` int(100) NOT NULL COMMENT '小数点处理 1向下取整 2向上取整 3四舍五入',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='分货单-渠道分货信息';

CREATE TABLE `rule_allocation_history` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '分配历史唯一标识',
  `rule_id` int(11) NOT NULL COMMENT '关联的分货单规则ID',
  `rule_name` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '分货单名称',
  `store_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '分货仓库编码',
  `allocation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '分配操作时间',
  `allocation_details` text COLLATE utf8mb4_bin NOT NULL COMMENT '分配详情JSON',
  `total_allocated` int(11) NOT NULL COMMENT '总分配数量',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '分配状态 0-待处理 1-成功 2-失败',
  `operator` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '操作人',
  `remark` text COLLATE utf8mb4_bin COMMENT '附加说明',
  `exceed_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '超额分配量',
  PRIMARY KEY (`id`),
  KEY `idx_rule_store` (`rule_id`,`store_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='库存分配历史记录表';

CREATE TABLE `rule_stock_goods_info` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `rule_id` int(11) NOT NULL COMMENT '规则ID rule_stock_info.id',
  `sku_sn` varchar(255) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL,
  `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='规则明细-指定商品信息表';

CREATE TABLE `rule_stock_info` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `enable` int(11) NOT NULL COMMENT '是否启用',
  `rule_type` int(11) NOT NULL COMMENT '分货单规则：1，日常分货；2，一次性分货；3，锁库时分货',
  `rule_code` varchar(100) COLLATE utf8mb4_bin NOT NULL COMMENT '分货单编号(系统唯一)',
  `rule_name` varchar(100) COLLATE utf8mb4_bin NOT NULL COMMENT '分货单名称',
  `rule_mode` tinyint(4) DEFAULT NULL COMMENT '分货模式 1，普通分配（超额）；2，优先分配；3，等比例压缩',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态 1新建 2待审核 3待执行 4执行中 5已结束 6已作废',
  `start_time` datetime DEFAULT NULL COMMENT '分货单有效期, 开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '分货单有效期, 结束时间',
  `rule_range` int(11) NOT NULL COMMENT '分货范围 : 1,全部商品; 2部分商品',
  `remark` text COLLATE utf8mb4_bin COMMENT '备注信息',
  `last_update_time` datetime DEFAULT NULL COMMENT '最新分货时间',
  `first_reviewer_time` datetime DEFAULT NULL COMMENT '首次审核时间',
  `reviewer_time` datetime DEFAULT NULL COMMENT '审核时间',
  `over_time` datetime DEFAULT NULL COMMENT '结束时间',
  `cancel_time` datetime DEFAULT NULL COMMENT '作废时间',
  `reviewer_user_name` varchar(255) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '审核人姓名',
  `create_user_name` varchar(255) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人姓名',
  `modify_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `company_code` varchar(50) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '公司编码',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='分货单基础信息表';



# 待实现功能
 
###  锁库单分货 （待实现）
- 实现锁库单分货的功能。 
- 注意锁库单不是分配策略，而是一种特殊类型的库存分配方式。所以不要添加锁库单的分配策略！！
- 锁库单的库存分配逻辑和普通的库存分配逻辑不同。
- 锁库单的库存分配逻辑是会预占仓库库存，然后分配库存。  
- 锁库单的更新字段与非锁库单的更新字段不同。
- 实现锁库单分货的优先级分配策略。
- 实现锁库单分货的超额分配策略。
- 实现锁库单分货的其他特殊情况处理。
- 实现锁库单分货的库存分配日志记录。
- 分配的库存更新到 channel_inventory_change_history 字段
- 锁库单分配后要维护wms_inventory.zp_lock_number 字段
- 锁库单分配后要维护wms_inventory.zp_available_number 字段
- 锁库单的库存分配日志记录到rule_allocation_history
- 锁库渠道库存变动记录到channel_inventory_change_history
- 锁库仓库库存（wms_inventory）变动记录也需要记录到日志表，目前没有数据库表，可以自己建。参考channel_inventory_change_history