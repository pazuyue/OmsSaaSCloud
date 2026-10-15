-- Fill missing comments only; preserve existing comments, data and definitions.

USE `qm_oms_saas_order`;
SET SESSION lock_wait_timeout=10;

ALTER TABLE `oms_order`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `platform_order_id` bigint(20) NOT NULL COMMENT '平台原始订单ID，关联oms_platform_order.id',
  MODIFY COLUMN `platform_tid` varchar(64) NOT NULL COMMENT '平台主订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `payment` decimal(18,2) NOT NULL COMMENT '实付金额，单位元',
  MODIFY COLUMN `receiver_name` varchar(200) NOT NULL DEFAULT '' COMMENT '收货人姓名快照',
  MODIFY COLUMN `receiver_phone` varchar(200) NOT NULL DEFAULT '' COMMENT '收货人联系电话快照',
  MODIFY COLUMN `receiver_address` varchar(1000) NOT NULL DEFAULT '' COMMENT '收货人详细地址快照',
  MODIFY COLUMN `oaid` varchar(200) NOT NULL DEFAULT '' COMMENT '平台收货信息关联标识OAID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  MODIFY COLUMN `allocation_status` varchar(24) NOT NULL DEFAULT 'NONE' COMMENT '订单分仓执行状态',
  MODIFY COLUMN `allocation_quantity` bigint(20) NOT NULL DEFAULT '0' COMMENT '订单累计分仓实物库存占用数量',
  MODIFY COLUMN `allocation_policy` text COMMENT '分仓时固定的策略快照JSON',
  MODIFY COLUMN `allocation_receipts` mediumtext COMMENT '分仓占用结果回执JSON',
  MODIFY COLUMN `allocation_error` varchar(500) NOT NULL DEFAULT '' COMMENT '最近一次分仓处理失败原因',
  MODIFY COLUMN `allocation_at` datetime DEFAULT NULL COMMENT '最近一次分仓处理时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_allocation_config`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `policy_json` text NOT NULL COMMENT '店铺订单分仓策略JSON',
  MODIFY COLUMN `revision` bigint(20) NOT NULL DEFAULT '1' COMMENT '配置或业务记录版本号',
  MODIFY COLUMN `updated_by` varchar(64) NOT NULL COMMENT '最近修改人账号',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  COMMENT='公司店铺订单分仓策略配置及版本',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_carrier`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `carrier_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'OMS快递公司编码',
  MODIFY COLUMN `carrier_name` varchar(100) NOT NULL COMMENT 'OMS快递公司名称快照',
  MODIFY COLUMN `enabled` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否启用：0否，1是',
  MODIFY COLUMN `services_json` text NOT NULL COMMENT '快递货主与实体仓服务范围JSON',
  MODIFY COLUMN `revision` bigint(20) NOT NULL DEFAULT '1' COMMENT '配置或业务记录版本号',
  MODIFY COLUMN `updated_by` varchar(100) NOT NULL COMMENT '最近修改人账号',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_carrier_config`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `policy_json` text NOT NULL COMMENT '店铺允许快递顺序与实仓省份匹配策略JSON',
  MODIFY COLUMN `revision` bigint(20) NOT NULL DEFAULT '1' COMMENT '配置或业务记录版本号',
  MODIFY COLUMN `updated_by` varchar(100) NOT NULL COMMENT '最近修改人账号',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_issue`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `order_id` bigint(20) NOT NULL COMMENT 'OMS业务订单ID，关联oms_order.id',
  MODIFY COLUMN `domain` varchar(32) NOT NULL COMMENT '问题所属业务域',
  MODIFY COLUMN `issue_key` varchar(64) NOT NULL COMMENT '问题唯一业务键，同一订单与问题域内唯一',
  MODIFY COLUMN `category` varchar(32) NOT NULL COMMENT '业务问题分类',
  MODIFY COLUMN `reason_code` varchar(64) NOT NULL COMMENT '业务问题原因编码',
  MODIFY COLUMN `scope` varchar(24) NOT NULL COMMENT '业务问题影响范围',
  MODIFY COLUMN `stage` varchar(24) NOT NULL COMMENT '业务问题发生阶段',
  MODIFY COLUMN `sku_sn` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '内部商品SKU编码',
  MODIFY COLUMN `severity` varchar(12) NOT NULL COMMENT '业务问题严重程度',
  MODIFY COLUMN `blocking` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否阻断履约：0否，1是',
  MODIFY COLUMN `state` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT '业务问题处理状态',
  MODIFY COLUMN `message` varchar(1000) NOT NULL COMMENT '业务处理说明或结果信息',
  MODIFY COLUMN `evidence_json` mediumtext NOT NULL COMMENT '业务问题依据快照JSON',
  MODIFY COLUMN `evidence_hash` varchar(64) NOT NULL COMMENT '业务问题依据快照哈希值',
  MODIFY COLUMN `first_seen` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务问题首次发现时间',
  MODIFY COLUMN `last_seen` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务问题最近发现时间',
  MODIFY COLUMN `resolved_at` datetime DEFAULT NULL COMMENT '业务问题解除时间',
  MODIFY COLUMN `acknowledged_by` varchar(64) NOT NULL DEFAULT '' COMMENT '业务问题确认操作人',
  MODIFY COLUMN `acknowledged_at` datetime DEFAULT NULL COMMENT '业务问题确认时间',
  MODIFY COLUMN `acknowledgement` varchar(500) NOT NULL DEFAULT '' COMMENT '业务问题人工确认说明',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_issue_event`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `order_id` bigint(20) NOT NULL COMMENT 'OMS业务订单ID，关联oms_order.id',
  MODIFY COLUMN `issue_id` bigint(20) NOT NULL COMMENT '订单业务问题ID，关联oms_order_issue.id',
  MODIFY COLUMN `action` varchar(24) NOT NULL COMMENT '业务操作类型',
  MODIFY COLUMN `actor` varchar(64) NOT NULL COMMENT '事件操作人或系统执行标识',
  MODIFY COLUMN `message` varchar(1000) NOT NULL COMMENT '业务处理说明或结果信息',
  MODIFY COLUMN `evidence_json` mediumtext NOT NULL COMMENT '业务问题依据快照JSON',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_line`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `order_id` bigint(20) NOT NULL COMMENT 'OMS业务订单ID，关联oms_order.id',
  MODIFY COLUMN `platform_oid` varchar(64) NOT NULL COMMENT '平台子订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `sku_sn` varchar(128) NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `goods_name` varchar(500) NOT NULL COMMENT '商品名称快照',
  MODIFY COLUMN `quantity` int(11) NOT NULL COMMENT '业务订单商品购买数量',
  MODIFY COLUMN `payment` decimal(18,2) NOT NULL COMMENT '实付金额，单位元',
  MODIFY COLUMN `reserved_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '订单行已逻辑预占数量，不代表仓库实物占用',
  MODIFY COLUMN `inventory_reason` varchar(500) NOT NULL DEFAULT '' COMMENT '订单行库存预占处理说明',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_platform_shipping_config`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `mode` varchar(16) NOT NULL DEFAULT 'OFF' COMMENT '店铺平台发货模式：OFF关闭，SIMULATION模拟',
  MODIFY COLUMN `carrier_mapping` mediumtext NOT NULL COMMENT 'OMS快递与平台快递编码映射JSON',
  MODIFY COLUMN `enabled_at` datetime DEFAULT NULL COMMENT '当前模式启用时间',
  MODIFY COLUMN `control_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '业务控制版本号，用于校验并发操作',
  MODIFY COLUMN `updated_by` varchar(64) NOT NULL DEFAULT '' COMMENT '最近修改人账号',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  COMMENT='公司店铺平台发货模式与快递映射配置',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_platform_shipping_log`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `task_id` bigint(20) NOT NULL COMMENT '平台发货任务ID，关联oms_order_platform_shipping_task.id',
  MODIFY COLUMN `action` varchar(24) NOT NULL COMMENT '业务操作类型',
  MODIFY COLUMN `result` varchar(16) NOT NULL COMMENT '业务操作结果',
  MODIFY COLUMN `message` varchar(500) NOT NULL COMMENT '业务处理说明或结果信息',
  MODIFY COLUMN `operator_name` varchar(64) NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  COMMENT='平台发货任务操作日志，记录提交、查询及重试结果',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_platform_shipping_sim_receipt`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `request_id` varchar(64) NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `payload_hash` char(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `receipt_code` varchar(100) NOT NULL COMMENT '模拟平台发货确认回执标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  COMMENT='平台发货模拟回执，按请求保存独立的幂等确认记录',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_platform_shipping_task`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `order_id` bigint(20) NOT NULL COMMENT 'OMS业务订单ID，关联oms_order.id',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `package_id` bigint(20) NOT NULL COMMENT '出库包裹ID，关联oms_order_shipment_package.id',
  MODIFY COLUMN `request_id` varchar(64) NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `mode` varchar(16) NOT NULL COMMENT '任务创建时固定的平台发货模式',
  MODIFY COLUMN `state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '业务处理状态',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '本次平台发货包裹商品总数量',
  MODIFY COLUMN `payload_json` mediumtext NOT NULL COMMENT '业务报文固定快照JSON',
  MODIFY COLUMN `payload_hash` char(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '累计处理尝试次数',
  MODIFY COLUMN `cycle_attempts` int(11) NOT NULL DEFAULT '0' COMMENT '当前重试周期的提交尝试次数',
  MODIFY COLUMN `query_attempts` int(11) NOT NULL DEFAULT '0' COMMENT '回执查询尝试次数',
  MODIFY COLUMN `next_attempt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次处理或重试时间',
  MODIFY COLUMN `lease_until` datetime DEFAULT NULL COMMENT '任务执行租约到期时间',
  MODIFY COLUMN `lease_token` varchar(32) NOT NULL DEFAULT '' COMMENT '任务执行租约标识，用于防止重复并发执行',
  MODIFY COLUMN `last_error` varchar(500) NOT NULL DEFAULT '' COMMENT '最近一次处理失败原因',
  MODIFY COLUMN `platform_receipt` varchar(100) NOT NULL DEFAULT '' COMMENT '平台发货确认回执标识',
  MODIFY COLUMN `created_by` varchar(64) NOT NULL DEFAULT '' COMMENT '创建操作人或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `success_at` datetime DEFAULT NULL COMMENT '业务处理成功时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  COMMENT='发货包裹平台发货任务，保存固定报文、幂等标识及重试查询进度',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_process_log`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `platform_order_id` bigint(20) NOT NULL COMMENT '平台原始订单ID，关联oms_platform_order.id',
  MODIFY COLUMN `action` varchar(32) NOT NULL COMMENT '业务操作类型',
  MODIFY COLUMN `result` varchar(24) NOT NULL COMMENT '业务操作结果',
  MODIFY COLUMN `message` varchar(500) NOT NULL COMMENT '业务处理说明或结果信息',
  MODIFY COLUMN `operator_name` varchar(100) NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_pull_config`
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_pull_item`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `task_id` bigint(20) NOT NULL COMMENT '补拉任务ID，关联oms_order_pull_task.id',
  MODIFY COLUMN `platform_tid` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '平台主订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `platform_order_id` bigint(20) DEFAULT NULL COMMENT '平台原始订单ID，关联oms_platform_order.id',
  MODIFY COLUMN `result` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '业务操作结果',
  MODIFY COLUMN `message` varchar(500) NOT NULL DEFAULT '' COMMENT '业务处理说明或结果信息',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  COMMENT='按平台单号补拉的逐单处理结果，关联补拉任务与原始订单',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_pull_task`
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `pulled_count` int(11) NOT NULL DEFAULT '0' COMMENT '从推送源拉取的记录数',
  MODIFY COLUMN `inserted_count` int(11) NOT NULL DEFAULT '0' COMMENT '新增登记的平台订单数',
  MODIFY COLUMN `duplicate_count` int(11) NOT NULL DEFAULT '0' COMMENT '重复平台订单记录数',
  MODIFY COLUMN `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '累计处理尝试次数',
  MODIFY COLUMN `next_retry_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次允许重试时间',
  MODIFY COLUMN `error_message` varchar(500) NOT NULL DEFAULT '' COMMENT '处理错误说明',
  MODIFY COLUMN `operator_name` varchar(100) NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  MODIFY COLUMN `pull_mode` varchar(12) NOT NULL DEFAULT 'TIME' COMMENT '补拉方式标识',
  MODIFY COLUMN `requested_tids` text COMMENT '按单号补拉的平台订单编号列表JSON',
  MODIFY COLUMN `request_key` varchar(64) DEFAULT NULL COMMENT '请求幂等键',
  MODIFY COLUMN `request_hash` varchar(64) NOT NULL DEFAULT '' COMMENT '请求内容哈希值，用于幂等内容校验',
  MODIFY COLUMN `pull_source_key` varchar(64) NOT NULL DEFAULT '' COMMENT '提交补拉任务时固定的推送源连接标识',
  MODIFY COLUMN `pull_seller_nick` varchar(200) NOT NULL DEFAULT '' COMMENT '提交补拉任务时固定的卖家账号',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_push_source`
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `source_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '推送源数据库连接标识',
  MODIFY COLUMN `host` varchar(253) NOT NULL COMMENT '推送源数据库主机地址',
  MODIFY COLUMN `port` int(11) NOT NULL DEFAULT '3306' COMMENT '推送源数据库端口',
  MODIFY COLUMN `database_name` varchar(64) NOT NULL COMMENT '推送源数据库名称',
  MODIFY COLUMN `username` varchar(64) NOT NULL COMMENT '推送源数据库用户名',
  MODIFY COLUMN `enabled` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否启用：0否，1是',
  MODIFY COLUMN `revision` int(11) NOT NULL DEFAULT '1' COMMENT '配置或业务记录版本号',
  MODIFY COLUMN `last_test_status` varchar(16) NOT NULL DEFAULT 'UNTESTED' COMMENT '最近一次推送源连接测试状态',
  MODIFY COLUMN `last_test_time` datetime DEFAULT NULL COMMENT '最近一次推送源连接测试时间',
  MODIFY COLUMN `last_test_message` varchar(200) NOT NULL DEFAULT '尚未测试' COMMENT '最近一次推送源连接测试说明',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_sales_document_sync`
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `revision` bigint(20) NOT NULL DEFAULT '0' COMMENT '当前待同步报文版本号',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL DEFAULT '' COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `payload_json` mediumtext COMMENT '业务报文固定快照JSON',
  MODIFY COLUMN `synced_revision` bigint(20) NOT NULL DEFAULT '0' COMMENT '已成功同步的报文版本号',
  MODIFY COLUMN `last_error` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次处理失败原因',
  MODIFY COLUMN `next_attempt` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '下一次处理或重试时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  MODIFY COLUMN `dirty_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '发货单变更标记版本号',
  MODIFY COLUMN `synced_dirty_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '已完成同步的发货单变更版本号',
  COMMENT='发货单向销售出库单同步的报文版本、变更版本及补偿进度',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `order_id` bigint(20) NOT NULL COMMENT 'OMS业务订单ID，关联oms_order.id',
  MODIFY COLUMN `order_sn` varchar(64) NOT NULL COMMENT 'OMS业务订单编号',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `platform_tid` varchar(64) NOT NULL COMMENT '平台主订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `generation_no` bigint(20) NOT NULL COMMENT '发货单生成轮次编号',
  MODIFY COLUMN `owner_code` varchar(64) NOT NULL COMMENT '库存货主编码',
  MODIFY COLUMN `real_store_code` varchar(255) NOT NULL COMMENT '实体仓库编码',
  MODIFY COLUMN `real_store_name` varchar(255) NOT NULL COMMENT '实体仓库名称快照',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '发货单计划出库商品总数量',
  MODIFY COLUMN `status` varchar(24) NOT NULL DEFAULT 'WAIT_CARRIER' COMMENT '发货单履约状态',
  MODIFY COLUMN `receiver_name` varchar(200) NOT NULL DEFAULT '' COMMENT '收货人姓名快照',
  MODIFY COLUMN `receiver_phone` varchar(200) NOT NULL DEFAULT '' COMMENT '收货人联系电话快照',
  MODIFY COLUMN `receiver_address` varchar(1000) NOT NULL DEFAULT '' COMMENT '收货人详细地址快照',
  MODIFY COLUMN `oaid` varchar(200) NOT NULL DEFAULT '' COMMENT '平台收货信息关联标识OAID',
  MODIFY COLUMN `created_by` varchar(100) NOT NULL COMMENT '创建操作人或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `cancelled_at` datetime DEFAULT NULL COMMENT '业务取消时间',
  MODIFY COLUMN `receiver_province` varchar(32) NOT NULL DEFAULT '' COMMENT '收货人所在省份，用于快递服务范围匹配',
  MODIFY COLUMN `carrier_code` varchar(64) NOT NULL DEFAULT '' COMMENT 'OMS快递公司编码',
  MODIFY COLUMN `carrier_name` varchar(100) NOT NULL DEFAULT '' COMMENT 'OMS快递公司名称快照',
  MODIFY COLUMN `warehouse_carrier_code` varchar(64) NOT NULL DEFAULT '' COMMENT 'WMS侧快递公司编码',
  MODIFY COLUMN `carrier_match_status` varchar(16) NOT NULL DEFAULT 'WAITING' COMMENT '发货单快递匹配状态',
  MODIFY COLUMN `carrier_match_mode` varchar(16) NOT NULL DEFAULT '' COMMENT '发货单快递匹配方式',
  MODIFY COLUMN `carrier_match_reason` varchar(500) NOT NULL DEFAULT '' COMMENT '发货单快递匹配结果或失败原因',
  MODIFY COLUMN `carrier_attempt_at` datetime DEFAULT NULL COMMENT '最近一次快递匹配尝试时间',
  MODIFY COLUMN `carrier_matched_at` datetime DEFAULT NULL COMMENT '快递匹配确认时间',
  MODIFY COLUMN `carrier_matched_by` varchar(100) NOT NULL DEFAULT '' COMMENT '快递匹配确认操作人',
  MODIFY COLUMN `carrier_snapshot` text COMMENT '发货单快递匹配依据快照JSON',
  MODIFY COLUMN `control_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '业务控制版本号，用于校验并发操作',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment_dispatch`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `payload_json` mediumtext NOT NULL COMMENT '业务报文固定快照JSON',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `state` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '业务处理状态',
  MODIFY COLUMN `remote_task_id` bigint(20) DEFAULT NULL COMMENT '仓库服务出库下发任务ID',
  MODIFY COLUMN `external_order` varchar(100) NOT NULL DEFAULT '' COMMENT 'WMS侧业务单号',
  MODIFY COLUMN `warehouse_status` varchar(40) NOT NULL DEFAULT '' COMMENT 'WMS侧业务状态',
  MODIFY COLUMN `last_error` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次处理失败原因',
  MODIFY COLUMN `query_message` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次仓库状态查询说明',
  MODIFY COLUMN `last_query_at` datetime DEFAULT NULL COMMENT '最近一次仓库状态查询时间',
  MODIFY COLUMN `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '累计处理尝试次数',
  MODIFY COLUMN `lease_token` varchar(64) DEFAULT NULL COMMENT '任务执行租约标识，用于防止重复并发执行',
  MODIFY COLUMN `lease_until` datetime DEFAULT NULL COMMENT '任务执行租约到期时间',
  MODIFY COLUMN `next_attempt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次处理或重试时间',
  MODIFY COLUMN `created_by` varchar(100) NOT NULL COMMENT '创建操作人或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment_line`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `allocation_id` bigint(20) NOT NULL COMMENT '订单分仓占用回执ID，关联库存库oms_order_warehouse_allocation.id',
  MODIFY COLUMN `order_line_id` bigint(20) NOT NULL COMMENT 'OMS业务订单明细ID，关联oms_order_line.id',
  MODIFY COLUMN `platform_oid` varchar(64) NOT NULL COMMENT '平台子订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `sku_sn` varchar(128) NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `goods_name` varchar(500) NOT NULL COMMENT '商品名称快照',
  MODIFY COLUMN `store_code` varchar(64) NOT NULL COMMENT '虚拟仓库编码',
  MODIFY COLUMN `batch_id` bigint(20) NOT NULL COMMENT '仓库库存批次ID，关联库存库wms_inventory_batch.id',
  MODIFY COLUMN `source_type` varchar(24) NOT NULL COMMENT '库存来源类型：LOCK渠道锁库，PUBLIC_QUOTA普通额度，PUBLIC_EXTRA公共补占',
  MODIFY COLUMN `stock_source_id` bigint(20) NOT NULL COMMENT '订单库存来源ID，关联oms_order_stock_source.id',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '发货行计划出库数量',
  MODIFY COLUMN `shipped_quantity` bigint(20) NOT NULL DEFAULT '0' COMMENT '发货行累计实际出库数量',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment_log`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `action` varchar(32) NOT NULL COMMENT '业务操作类型',
  MODIFY COLUMN `result` varchar(24) NOT NULL COMMENT '业务操作结果',
  MODIFY COLUMN `message` varchar(500) NOT NULL COMMENT '业务处理说明或结果信息',
  MODIFY COLUMN `operator_name` varchar(100) NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment_outbound`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `source_mode` varchar(12) NOT NULL COMMENT '出库来源方式：VIRTUAL虚拟出库，WMS仓库出库回传',
  MODIFY COLUMN `remote_event_id` bigint(20) DEFAULT NULL COMMENT '仓库服务出库回传事件ID',
  MODIFY COLUMN `message_id` varchar(128) NOT NULL DEFAULT '' COMMENT '仓库回传消息标识，用于重复消息去重',
  MODIFY COLUMN `final_confirmation` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否仓库最终出库确认：0否，1是',
  MODIFY COLUMN `outbound_time` datetime DEFAULT NULL COMMENT '实际出库发生时间',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '本次出库确认的商品总数量',
  MODIFY COLUMN `payload_json` mediumtext NOT NULL COMMENT '业务报文固定快照JSON',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `state` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '业务处理状态',
  MODIFY COLUMN `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '累计处理尝试次数',
  MODIFY COLUMN `last_error` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次处理失败原因',
  MODIFY COLUMN `created_by` varchar(100) NOT NULL COMMENT '创建操作人或系统执行标识',
  MODIFY COLUMN `next_attempt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次处理或重试时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `completed_at` datetime DEFAULT NULL COMMENT '业务处理完成时间',
  COMMENT='发货单虚拟出库及WMS出库回传记录，保存库存入账指令与处理结果',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment_package`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `outbound_id` bigint(20) DEFAULT NULL COMMENT '发货单出库记录ID，关联oms_order_shipment_outbound.id',
  MODIFY COLUMN `package_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '出库包裹编号',
  MODIFY COLUMN `carrier_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'OMS快递公司编码',
  MODIFY COLUMN `carrier_name` varchar(100) NOT NULL COMMENT 'OMS快递公司名称快照',
  MODIFY COLUMN `warehouse_carrier_code` varchar(64) NOT NULL DEFAULT '' COMMENT 'WMS侧快递公司编码',
  MODIFY COLUMN `express_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '快递运单编号',
  MODIFY COLUMN `source_mode` varchar(12) NOT NULL COMMENT '出库来源方式：VIRTUAL虚拟出库，WMS仓库出库回传',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '包裹实际出库商品总数量',
  MODIFY COLUMN `outbound_time` datetime DEFAULT NULL COMMENT '实际出库发生时间',
  MODIFY COLUMN `created_by` varchar(100) NOT NULL COMMENT '创建操作人或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `shared_review_id` bigint(20) DEFAULT NULL COMMENT '允许共用运单的人工核对记录ID',
  MODIFY COLUMN `waybill_slot` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '共用运单包裹区分标识，普通独占运单为空',
  COMMENT='发货单实际出库包裹，保存快递运单、出库数量及共用运单核对依据',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment_package_line`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `package_id` bigint(20) NOT NULL COMMENT '出库包裹ID，关联oms_order_shipment_package.id',
  MODIFY COLUMN `shipment_line_id` bigint(20) NOT NULL COMMENT '发货单商品明细ID，关联oms_order_shipment_line.id',
  MODIFY COLUMN `sku_sn` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `platform_oid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '平台子订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '包裹中该发货行的实际出库数量',
  COMMENT='实际出库包裹商品明细，关联发货行与平台子订单',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_shipment_virtual_outbound`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `payload_json` mediumtext NOT NULL COMMENT '业务报文固定快照JSON',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `state` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '业务处理状态',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '本次虚拟出库指令的商品总数量',
  MODIFY COLUMN `last_error` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次处理失败原因',
  MODIFY COLUMN `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '累计处理尝试次数',
  MODIFY COLUMN `next_attempt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次处理或重试时间',
  MODIFY COLUMN `created_by` varchar(100) NOT NULL COMMENT '创建操作人或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `completed_at` datetime DEFAULT NULL COMMENT '业务处理完成时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_waybill_lock`
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `carrier_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'OMS快递公司编码',
  MODIFY COLUMN `express_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '快递运单编号',
  COMMENT='公司快递运单操作锁，协调订单包裹与运单的并发登记',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_platform_order`
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `platform` varchar(16) NOT NULL DEFAULT 'TMALL' COMMENT '订单来源平台编码',
  MODIFY COLUMN `platform_status` varchar(64) NOT NULL DEFAULT '' COMMENT '平台订单状态',
  MODIFY COLUMN `trade_type` varchar(64) NOT NULL DEFAULT '' COMMENT '平台交易类型',
  MODIFY COLUMN `seller_nick` varchar(200) NOT NULL DEFAULT '' COMMENT '平台卖家账号',
  MODIFY COLUMN `platform_created` datetime DEFAULT NULL COMMENT '平台订单创建时间',
  MODIFY COLUMN `platform_modified` datetime DEFAULT NULL COMMENT '平台订单修改时间',
  MODIFY COLUMN `source_modified` datetime NOT NULL COMMENT '推送源记录修改时间，用于拉单增量分页',
  MODIFY COLUMN `payload_hash` char(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `processing_hash` char(64) DEFAULT NULL COMMENT '固定转单报文快照的哈希值',
  MODIFY COLUMN `conversion_error` varchar(500) NOT NULL DEFAULT '' COMMENT '转单失败或等待原因',
  MODIFY COLUMN `demand_registered` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否已登记订单库存需求：0否，1是',
  MODIFY COLUMN `next_retry_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次允许重试时间',
  MODIFY COLUMN `business_order_id` bigint(20) DEFAULT NULL COMMENT '转单生成的OMS业务订单ID',
  MODIFY COLUMN `payment` decimal(18,2) DEFAULT NULL COMMENT '实付金额，单位元',
  MODIFY COLUMN `receiver_name` varchar(200) NOT NULL DEFAULT '' COMMENT '收货人姓名快照',
  MODIFY COLUMN `receiver_phone` varchar(200) NOT NULL DEFAULT '' COMMENT '收货人联系电话快照',
  MODIFY COLUMN `receiver_address` varchar(1000) NOT NULL DEFAULT '' COMMENT '收货人详细地址快照',
  MODIFY COLUMN `oaid` varchar(200) NOT NULL DEFAULT '' COMMENT '平台收货信息关联标识OAID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_platform_order_line`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `platform_order_id` bigint(20) NOT NULL COMMENT '平台原始订单ID，关联oms_platform_order.id',
  MODIFY COLUMN `platform_oid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '平台子订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `platform_item_id` varchar(64) NOT NULL DEFAULT '' COMMENT '平台商品ID',
  MODIFY COLUMN `platform_sku_id` varchar(64) NOT NULL DEFAULT '' COMMENT '平台商品SKU ID',
  MODIFY COLUMN `outer_sku_id` varchar(128) NOT NULL DEFAULT '' COMMENT '平台商品外部SKU编码，用于匹配内部SKU',
  MODIFY COLUMN `outer_iid` varchar(128) NOT NULL DEFAULT '' COMMENT '平台商品外部货号，用于匹配内部商品',
  MODIFY COLUMN `title` varchar(500) NOT NULL DEFAULT '' COMMENT '平台商品标题快照',
  MODIFY COLUMN `quantity` int(11) NOT NULL COMMENT '平台子订单商品购买数量',
  MODIFY COLUMN `payment` decimal(18,2) DEFAULT NULL COMMENT '实付金额，单位元',
  MODIFY COLUMN `match_error` varchar(500) NOT NULL DEFAULT '' COMMENT '商品匹配失败原因',
  ALGORITHM=INPLACE,
  LOCK=NONE;

USE `qm_oms_saas_inventory`;
SET SESSION lock_wait_timeout=10;

ALTER TABLE `channel_inventory_change_history`
  MODIFY COLUMN `version` int(11) DEFAULT NULL COMMENT '库存记录版本号，用于并发更新控制',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_inventory`
  MODIFY COLUMN `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_stock_demand`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `platform_tid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '平台主订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `platform_oid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '平台子订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `sku_sn` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `owner_code` varchar(64) DEFAULT NULL COMMENT '库存需求货主编码，逻辑登记阶段可为空，分仓时确定',
  MODIFY COLUMN `store_codes` text COMMENT '有效虚仓库存范围快照JSON，逻辑登记阶段可为空',
  MODIFY COLUMN `allow_public_stock` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否允许超出普通渠道额度补占公共库存：0否，1是',
  MODIFY COLUMN `demand_quantity` int(11) NOT NULL COMMENT '订单行销售需求数量',
  MODIFY COLUMN `reason` varchar(500) NOT NULL DEFAULT '' COMMENT '业务处理原因或说明',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  MODIFY COLUMN `outbound_quantity` bigint(20) NOT NULL DEFAULT '0' COMMENT '累计已出库数量',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_stock_event`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `demand_id` bigint(20) NOT NULL COMMENT '订单库存需求ID，关联oms_order_stock_demand.id',
  MODIFY COLUMN `action` varchar(24) NOT NULL COMMENT '业务操作类型',
  MODIFY COLUMN `quantity` int(11) NOT NULL COMMENT '本次订单库存登记或逻辑预占数量',
  MODIFY COLUMN `message` varchar(500) NOT NULL COMMENT '业务处理说明或结果信息',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_stock_source`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `demand_id` bigint(20) NOT NULL COMMENT '订单库存需求ID，关联oms_order_stock_demand.id',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `sku_sn` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `store_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '虚拟仓库编码',
  MODIFY COLUMN `quantity` int(11) NOT NULL COMMENT '该库存来源承担的订单需求数量',
  MODIFY COLUMN `outbound_quantity` bigint(20) NOT NULL DEFAULT '0' COMMENT '累计已出库数量',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_virtual_outbound`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `platform_tid` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '平台主订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `receipt_json` mediumtext NOT NULL COMMENT '处理回执快照JSON',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '本次库存出库入账的商品总数量',
  MODIFY COLUMN `operator_name` varchar(100) NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `source_mode` varchar(12) NOT NULL DEFAULT 'VIRTUAL' COMMENT '出库来源方式：VIRTUAL虚拟出库，WMS仓库出库回传',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `oms_order_warehouse_allocation`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `platform_tid` varchar(64) NOT NULL COMMENT '平台主订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `demand_id` bigint(20) NOT NULL COMMENT '订单库存需求ID，关联oms_order_stock_demand.id',
  MODIFY COLUMN `stock_source_id` bigint(20) NOT NULL COMMENT '订单库存来源ID，关联oms_order_stock_source.id',
  MODIFY COLUMN `sku_sn` varchar(128) NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `source_type` varchar(24) NOT NULL COMMENT '库存来源类型：LOCK渠道锁库，PUBLIC_QUOTA普通额度，PUBLIC_EXTRA公共补占',
  MODIFY COLUMN `batch_id` bigint(20) NOT NULL COMMENT '仓库库存批次ID，关联库存库wms_inventory_batch.id',
  MODIFY COLUMN `store_code` varchar(64) NOT NULL COMMENT '虚拟仓库编码',
  MODIFY COLUMN `owner_code` varchar(64) NOT NULL COMMENT '库存货主编码',
  MODIFY COLUMN `real_store_code` varchar(255) NOT NULL COMMENT '实体仓库编码',
  MODIFY COLUMN `real_store_name` varchar(255) NOT NULL COMMENT '实体仓库名称快照',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '本条分仓回执占用的实物库存数量',
  MODIFY COLUMN `status` varchar(24) NOT NULL DEFAULT 'OCCUPIED' COMMENT '仓库占用回执状态',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近更新时间',
  MODIFY COLUMN `outbound_quantity` bigint(20) NOT NULL DEFAULT '0' COMMENT '累计已出库数量',
  MODIFY COLUMN `outbound_shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '消耗该占用的发货单编号',
  MODIFY COLUMN `outbound_method` varchar(12) NOT NULL DEFAULT 'VIRTUAL' COMMENT '占用消耗的出库方式：VIRTUAL虚拟出库，WMS仓库出库回传',
  COMMENT='订单分仓实物库存占用回执，保留货主、实仓、虚仓、批次及累计出库进度',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_channel_info`
  MODIFY COLUMN `id` int(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `priority` int(11) NOT NULL DEFAULT '0' COMMENT '渠道分货顺序优先级',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_goods_info`
  MODIFY COLUMN `id` int(10) unsigned NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `sku_sn` varchar(255) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '内部商品SKU编码',
  MODIFY COLUMN `create_time` datetime NOT NULL COMMENT '记录创建时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_info`
  MODIFY COLUMN `revision` int(11) NOT NULL DEFAULT '1' COMMENT '配置或业务记录版本号',
  MODIFY COLUMN `run_action` varchar(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'ALLOCATE' COMMENT '分货执行动作标识',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_order_reservation`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `rule_id` bigint(20) NOT NULL COMMENT '分货单ID，关联rule_stock_info.id',
  MODIFY COLUMN `sku_sn` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `order_line` varchar(100) COLLATE utf8mb4_bin NOT NULL COMMENT '订单行业务标识，用于锁库占用来源归属',
  MODIFY COLUMN `source_id` bigint(20) NOT NULL COMMENT '锁库分货来源ID，关联rule_stock_reservation.id',
  MODIFY COLUMN `original_quantity` int(11) NOT NULL COMMENT '来源初始数量',
  MODIFY COLUMN `occupied_quantity` int(11) NOT NULL COMMENT '当前已被订单占用但尚未消耗的数量',
  MODIFY COLUMN `consumed_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '累计已出库消耗数量',
  MODIFY COLUMN `cancelled_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '累计取消的占用数量',
  COMMENT='订单行对锁库分货来源的占用余额及消耗、取消数量',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_reservation`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `rule_id` bigint(20) NOT NULL COMMENT '分货单ID，关联rule_stock_info.id',
  MODIFY COLUMN `sku_sn` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `batch_id` bigint(20) NOT NULL COMMENT '仓库库存批次ID，关联库存库wms_inventory_batch.id',
  MODIFY COLUMN `original_quantity` int(11) NOT NULL COMMENT '来源初始数量',
  MODIFY COLUMN `occupied_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '当前已被订单占用但尚未消耗的数量',
  MODIFY COLUMN `consumed_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '累计已出库消耗数量',
  MODIFY COLUMN `released_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '累计已释放数量',
  COMMENT='锁库分货按商品、渠道与批次保存的来源余额及占用、消耗、释放数量',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_reservation_event`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `rule_id` bigint(20) NOT NULL COMMENT '分货单ID，关联rule_stock_info.id',
  MODIFY COLUMN `sku_sn` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `request_id` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `action` varchar(16) COLLATE utf8mb4_bin NOT NULL COMMENT '业务操作类型',
  MODIFY COLUMN `order_line` varchar(100) COLLATE utf8mb4_bin NOT NULL COMMENT '订单行业务标识，用于锁库占用来源归属',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `quantity` int(11) NOT NULL COMMENT '本次锁库占用、消耗或取消数量',
  MODIFY COLUMN `journal_request` varchar(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '关联库存变更流水的请求标识',
  MODIFY COLUMN `operator_name` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  COMMENT='锁库分货订单占用、消耗和取消操作的幂等事件流水',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_result`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `rule_id` bigint(20) NOT NULL COMMENT '分货单ID，关联rule_stock_info.id',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `sku_sn` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `status` varchar(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING' COMMENT '业务状态',
  MODIFY COLUMN `release_status` varchar(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'NONE' COMMENT '分货释放处理状态',
  MODIFY COLUMN `allocated_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '分货执行分配数量',
  MODIFY COLUMN `detail_json` mediumtext COLLATE utf8mb4_bin COMMENT '分货执行明细JSON',
  MODIFY COLUMN `error_message` varchar(500) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '处理错误说明',
  MODIFY COLUMN `release_error` varchar(500) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '分货释放失败原因',
  MODIFY COLUMN `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '累计处理尝试次数',
  MODIFY COLUMN `operator_name` varchar(255) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `release_operator` varchar(255) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '分货释放操作人',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  MODIFY COLUMN `occupied_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '当前已被订单占用但尚未消耗的数量',
  MODIFY COLUMN `consumed_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '累计已出库消耗数量',
  MODIFY COLUMN `released_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '累计已释放数量',
  COMMENT='分货单逐商品执行结果，记录分配、释放进度及来源数量汇总',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `rule_stock_store_code_info`
  MODIFY COLUMN `id` int(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_inventory`
  MODIFY COLUMN `store_code` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '虚拟仓库编码',
  MODIFY COLUMN `sku_sn` varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `version` int(11) DEFAULT '1' COMMENT '库存记录版本号，用于并发更新控制',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_inventory_batch`
  MODIFY COLUMN `store_code` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '虚拟仓库编码',
  MODIFY COLUMN `sku_sn` varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `batch_code` varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库库存批次编号',
  MODIFY COLUMN `company_code` varchar(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `version` int(11) DEFAULT '1' COMMENT '库存记录版本号，用于并发更新控制',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_inventory_change_history`
  MODIFY COLUMN `log_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '库存变更日志主键ID',
  MODIFY COLUMN `company_code` varchar(50) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `store_code` varchar(255) NOT NULL COMMENT '虚拟仓库编码',
  MODIFY COLUMN `sku_sn` varchar(255) NOT NULL COMMENT '内部商品SKU编码',
  MODIFY COLUMN `batch_id` bigint(20) DEFAULT NULL COMMENT '仓库库存批次ID，关联库存库wms_inventory_batch.id',
  MODIFY COLUMN `batch_code` varchar(255) DEFAULT NULL COMMENT '仓库库存批次编号',
  MODIFY COLUMN `inventory_type` varchar(2) DEFAULT NULL COMMENT '库存品类类型标识，区分正品与次品',
  MODIFY COLUMN `operation_type` varchar(16) NOT NULL COMMENT '库存变更操作类型',
  MODIFY COLUMN `request_id` varchar(64) DEFAULT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `relation_sn` varchar(128) DEFAULT NULL COMMENT '库存变更关联业务单号',
  MODIFY COLUMN `change_quantity` decimal(18,0) NOT NULL DEFAULT '0' COMMENT '本次库存变更数量',
  MODIFY COLUMN `change_reason` varchar(255) DEFAULT NULL COMMENT '本次库存变更原因',
  MODIFY COLUMN `operator_id` bigint(20) DEFAULT NULL COMMENT '操作人ID',
  MODIFY COLUMN `operator_name` varchar(64) DEFAULT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `old_zp_actual_number` int(11) DEFAULT NULL COMMENT '变更前正品实际库存数量',
  MODIFY COLUMN `new_zp_actual_number` int(11) DEFAULT NULL COMMENT '变更后正品实际库存数量',
  MODIFY COLUMN `old_zp_available_number` int(11) DEFAULT NULL COMMENT '变更前正品可用库存数量',
  MODIFY COLUMN `new_zp_available_number` int(11) DEFAULT NULL COMMENT '变更后正品可用库存数量',
  MODIFY COLUMN `old_zp_lock_number` int(11) DEFAULT NULL COMMENT '变更前正品锁定库存数量',
  MODIFY COLUMN `new_zp_lock_number` int(11) DEFAULT NULL COMMENT '变更后正品锁定库存数量',
  MODIFY COLUMN `old_cp_actual_number` int(11) DEFAULT NULL COMMENT '变更前次品实际库存数量',
  MODIFY COLUMN `new_cp_actual_number` int(11) DEFAULT NULL COMMENT '变更后次品实际库存数量',
  MODIFY COLUMN `old_cp_available_number` int(11) DEFAULT NULL COMMENT '变更前次品可用库存数量',
  MODIFY COLUMN `new_cp_available_number` int(11) DEFAULT NULL COMMENT '变更后次品可用库存数量',
  MODIFY COLUMN `old_cp_lock_number` int(11) DEFAULT NULL COMMENT '变更前次品锁定库存数量',
  MODIFY COLUMN `new_cp_lock_number` int(11) DEFAULT NULL COMMENT '变更后次品锁定库存数量',
  MODIFY COLUMN `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '库存业务操作发生时间',
  MODIFY COLUMN `remark` varchar(500) DEFAULT NULL COMMENT '业务备注说明',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  COMMENT='仓库库存变更流水，记录虚仓商品批次及正次品库存变更前后数量',
  ALGORITHM=INPLACE,
  LOCK=NONE;

USE `qm_oms_saas_commodity`;
SET SESSION lock_wait_timeout=10;

ALTER TABLE `company_model_association_info`
  MODIFY COLUMN `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(64) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `order_transfer_model` int(11) NOT NULL DEFAULT '1' COMMENT '旧版订单转单插件关联标识',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `goods_import_batch`
  MODIFY COLUMN `import_batch` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '商品导入批次标识',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `status` varchar(20) NOT NULL DEFAULT 'PREVIEW' COMMENT '商品导入批次状态：PREVIEW预览，COMPLETED已确认导入',
  MODIFY COLUMN `total_rows` int(11) NOT NULL COMMENT '导入数据总行数',
  MODIFY COLUMN `error_rows` int(11) NOT NULL COMMENT '导入校验失败行数',
  MODIFY COLUMN `create_user` varchar(100) DEFAULT NULL COMMENT '创建人账号',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `confirm_user` varchar(100) DEFAULT NULL COMMENT '确认人账号',
  MODIFY COLUMN `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  COMMENT='商品导入批次，保存预览校验统计及确认导入记录',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `goods_import_row`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `import_batch` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '商品导入批次标识',
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `row_num` int(11) NOT NULL COMMENT '导入文件行号',
  MODIFY COLUMN `payload` longtext NOT NULL COMMENT '导入商品行数据快照JSON',
  MODIFY COLUMN `notes` text NOT NULL COMMENT '导入商品行校验说明',
  COMMENT='商品导入预览明细，保存逐行数据快照与校验说明',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `goods_master_lock`
  MODIFY COLUMN `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  COMMENT='公司商品主数据操作锁，协调商品维护与导入的并发操作',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `goods_sku_sn_info`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `po_info`
  MODIFY COLUMN `expected_date` date DEFAULT NULL COMMENT '采购预计到货日期',
  MODIFY COLUMN `close_reason` varchar(500) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '采购单关闭原因',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `supplier_info`
  MODIFY COLUMN `status` int(11) NOT NULL DEFAULT '2' COMMENT '供应商启用状态',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_outbound_receipt_event`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `task_id` bigint(20) NOT NULL COMMENT 'WMS出库下发任务ID，关联wms_outbound_task.id',
  MODIFY COLUMN `shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `message_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '仓库回传消息标识，用于重复消息去重',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `payload_json` mediumtext NOT NULL COMMENT '业务报文固定快照JSON',
  MODIFY COLUMN `quantity` bigint(20) NOT NULL COMMENT '本次仓库出库回传确认的商品总数量',
  MODIFY COLUMN `final_confirmation` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否仓库最终出库确认：0否，1是',
  MODIFY COLUMN `post_state` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '回传事件向订单服务提交状态',
  MODIFY COLUMN `post_error` varchar(1000) NOT NULL DEFAULT '' COMMENT '回传事件向订单服务提交失败原因',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `posted_at` datetime DEFAULT NULL COMMENT '回传事件成功提交时间',
  COMMENT='WMS出库回传事件，按消息去重并记录向订单服务提交结果',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_outbound_receipt_package`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `task_id` bigint(20) NOT NULL COMMENT 'WMS出库下发任务ID，关联wms_outbound_task.id',
  MODIFY COLUMN `event_id` bigint(20) NOT NULL COMMENT 'WMS出库回传事件ID，关联wms_outbound_receipt_event.id',
  MODIFY COLUMN `package_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '出库包裹编号',
  MODIFY COLUMN `express_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '快递运单编号',
  MODIFY COLUMN `logistics_code` varchar(64) NOT NULL COMMENT '仓库回传的快递公司编码',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `lines_json` mediumtext NOT NULL COMMENT '包裹商品明细快照JSON',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `waybill_slot` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '共用运单包裹区分标识，普通独占运单为空',
  COMMENT='WMS出库回传包裹及商品明细快照，保留包裹和运单去重依据',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_outbound_receipt_review`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `task_id` bigint(20) NOT NULL COMMENT 'WMS出库下发任务ID，关联wms_outbound_task.id',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `shipment_sn` varchar(64) NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `message_id` varchar(128) NOT NULL COMMENT '仓库回传消息标识，用于重复消息去重',
  MODIFY COLUMN `receipt_hash` varchar(64) NOT NULL COMMENT '回传报文哈希值，用于重复回传校验',
  MODIFY COLUMN `receipt_json` mediumtext NOT NULL COMMENT '处理回执快照JSON',
  MODIFY COLUMN `conflict_code` varchar(32) NOT NULL COMMENT '出库回传冲突原因编码',
  MODIFY COLUMN `conflict_message` varchar(1000) NOT NULL COMMENT '出库回传冲突详情',
  MODIFY COLUMN `source_log_id` bigint(20) NOT NULL COMMENT '原始仓库交互日志ID，关联wms_interaction_log.id',
  MODIFY COLUMN `state` varchar(16) NOT NULL DEFAULT 'WAIT_REVIEW' COMMENT '出库回传人工核对处理状态',
  MODIFY COLUMN `control_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '业务控制版本号，用于校验并发操作',
  MODIFY COLUMN `event_id` bigint(20) DEFAULT NULL COMMENT 'WMS出库回传事件ID，关联wms_outbound_receipt_event.id',
  MODIFY COLUMN `decision` varchar(32) NOT NULL DEFAULT '' COMMENT '人工核对处理决定',
  MODIFY COLUMN `reason` varchar(200) NOT NULL DEFAULT '' COMMENT '人工核对处理理由',
  MODIFY COLUMN `operator_name` varchar(100) NOT NULL DEFAULT '' COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `decided_at` datetime DEFAULT NULL COMMENT '人工核对决定时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  COMMENT='WMS出库回传冲突人工核对记录，保存原回传、冲突原因及处理决定',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_outbound_task`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `shipment_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `connection_id` bigint(20) NOT NULL COMMENT '仓库平台连接配置ID，关联wms_connection.id',
  MODIFY COLUMN `external_warehouse` varchar(100) NOT NULL COMMENT 'WMS侧仓库编码快照',
  MODIFY COLUMN `external_owner` varchar(100) NOT NULL COMMENT 'WMS侧货主编码快照',
  MODIFY COLUMN `owner_code` varchar(64) NOT NULL COMMENT '库存货主编码',
  MODIFY COLUMN `real_store_code` varchar(255) NOT NULL COMMENT '实体仓库编码',
  MODIFY COLUMN `payload_json` mediumtext NOT NULL COMMENT '业务报文固定快照JSON',
  MODIFY COLUMN `payload_hash` varchar(64) NOT NULL COMMENT '业务报文哈希值，用于幂等校验',
  MODIFY COLUMN `dispatch_state` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT '向仓库下发处理状态',
  MODIFY COLUMN `external_order` varchar(100) NOT NULL DEFAULT '' COMMENT 'WMS侧业务单号',
  MODIFY COLUMN `warehouse_status` varchar(40) NOT NULL DEFAULT '' COMMENT 'WMS侧业务状态',
  MODIFY COLUMN `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '累计处理尝试次数',
  MODIFY COLUMN `lease_token` varchar(64) DEFAULT NULL COMMENT '任务执行租约标识，用于防止重复并发执行',
  MODIFY COLUMN `lease_until` datetime DEFAULT NULL COMMENT '任务执行租约到期时间',
  MODIFY COLUMN `last_error` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次处理失败原因',
  MODIFY COLUMN `query_message` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次仓库状态查询说明',
  MODIFY COLUMN `last_query_time` datetime DEFAULT NULL COMMENT '最近一次仓库状态查询时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_outbound_waybill_lock`
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `logistics_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '仓库回传的快递公司编码',
  MODIFY COLUMN `express_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '快递运单编号',
  COMMENT='公司快递运单操作锁，协调出库回传和共用运单核对',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_sales_outbound_document`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  MODIFY COLUMN `shipment_sn` varchar(64) NOT NULL COMMENT '发货单编号',
  MODIFY COLUMN `store_code` varchar(255) NOT NULL COMMENT '虚拟仓库编码',
  MODIFY COLUMN `ticket_id` bigint(20) NOT NULL COMMENT 'WMS出入库单据ID，关联wms_tickets.id',
  MODIFY COLUMN `order_id` bigint(20) NOT NULL COMMENT 'OMS业务订单ID，关联oms_order.id',
  MODIFY COLUMN `platform_tid` varchar(100) NOT NULL COMMENT '平台主订单编号，以字符串保存避免精度丢失',
  MODIFY COLUMN `channel_id` bigint(20) NOT NULL COMMENT '店铺渠道ID',
  MODIFY COLUMN `channel_name` varchar(255) NOT NULL COMMENT '店铺渠道名称快照',
  MODIFY COLUMN `source_version` bigint(20) NOT NULL COMMENT '来源发货单快照版本号',
  MODIFY COLUMN `source_hash` varchar(64) NOT NULL COMMENT '来源发货单快照哈希值',
  MODIFY COLUMN `source_status` varchar(32) NOT NULL COMMENT '来源发货单状态',
  MODIFY COLUMN `dispatch_state` varchar(32) NOT NULL COMMENT '向仓库下发处理状态',
  MODIFY COLUMN `external_order` varchar(100) NOT NULL DEFAULT '' COMMENT 'WMS侧业务单号',
  MODIFY COLUMN `warehouse_status` varchar(40) NOT NULL DEFAULT '' COMMENT 'WMS侧业务状态',
  MODIFY COLUMN `last_error` varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次处理失败原因',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最近修改时间',
  COMMENT='销售发货单按虚仓同步的出库执行单，关联WMS单据及来源版本',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_sales_outbound_lock`
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  COMMENT='销售发货单出库同步锁，按公司与发货单串行处理',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_sales_outbound_movement`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  MODIFY COLUMN `company_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `ticket_id` bigint(20) NOT NULL COMMENT 'WMS出入库单据ID，关联wms_tickets.id',
  MODIFY COLUMN `ticket_line_id` bigint(20) NOT NULL COMMENT 'WMS出入库商品明细ID，关联wms_tickets_goods.id',
  MODIFY COLUMN `source_line_id` bigint(20) NOT NULL COMMENT '来源发货单明细ID，关联订单库oms_order_shipment_line.id',
  MODIFY COLUMN `request_id` varchar(64) NOT NULL COMMENT '业务请求幂等标识',
  MODIFY COLUMN `source_mode` varchar(12) NOT NULL COMMENT '出库来源方式：VIRTUAL虚拟出库，WMS仓库出库回传',
  MODIFY COLUMN `quantity` int(11) NOT NULL COMMENT '本次销售出库实际发生数量',
  MODIFY COLUMN `batch_id` bigint(20) NOT NULL COMMENT '仓库库存批次ID，关联库存库wms_inventory_batch.id',
  MODIFY COLUMN `operator_name` varchar(64) NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `outbound_time` datetime NOT NULL COMMENT '实际出库发生时间',
  COMMENT='销售出库实际发生明细，按请求与发货行去重并保留批次追溯',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_tickets`
  MODIFY COLUMN `inventory_status` int(11) NOT NULL DEFAULT '0' COMMENT '库存入账状态：0待处理，2成功，3异常',
  MODIFY COLUMN `business_type` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '出入库业务类型，如PURCHASE_IN采购入库、SALE_OUT销售出库',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `wms_tickets_goods`
  MODIFY COLUMN `error_info` varchar(500) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '出入库商品明细库存处理错误说明',
  MODIFY COLUMN `source_line_id` bigint(20) DEFAULT NULL COMMENT '来源发货单明细ID，关联订单库oms_order_shipment_line.id',
  ALGORITHM=INPLACE,
  LOCK=NONE;
