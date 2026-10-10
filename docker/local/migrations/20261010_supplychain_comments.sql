-- Comments only; existing types, defaults, charset and indexes are preserved.
USE `qm_oms_saas_commodity`;
SET SESSION lock_wait_timeout=10;

ALTER TABLE `purchase_line`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '采购计划明细主键ID',
  MODIFY COLUMN `company_code` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `po_sn` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '采购单号，关联同公司po_info.po_sn',
  MODIFY COLUMN `sku_sn` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '商品SKU编码，同一公司同一采购单内唯一',
  MODIFY COLUMN `goods_name` varchar(255) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '采购商品名称快照',
  MODIFY COLUMN `goods_sn` varchar(255) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '采购商品货号快照',
  MODIFY COLUMN `barcode_sn` varchar(255) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '采购商品条码快照',
  MODIFY COLUMN `quantity` int(11) NOT NULL COMMENT '计划采购数量，用于控制分批到货总量',
  MODIFY COLUMN `purchase_price` decimal(15,2) NOT NULL COMMENT '采购单价，保留两位小数；计划金额为数量乘单价',
  COMMENT='采购计划商品明细表，保存采购商品、数量和单价，审核后锁定计划',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `purchase_event`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '业务操作事件主键ID',
  MODIFY COLUMN `company_code` varchar(64) NOT NULL COMMENT '所属公司编码，用于租户隔离',
  MODIFY COLUMN `document_sn` varchar(255) NOT NULL COMMENT '业务对象编码：采购单号、到货单号、出入库单号或供应商编码',
  MODIFY COLUMN `action` varchar(64) NOT NULL COMMENT '操作名称，如创建、审核、收货、入账、作废或数据修复',
  MODIFY COLUMN `operator` varchar(64) NOT NULL COMMENT '操作人账号或系统执行标识',
  MODIFY COLUMN `message` varchar(1000) NOT NULL COMMENT '操作说明、处理结果或失败原因',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作事件记录时间',
  COMMENT='采购工作台业务操作日志表，记录供应商、采购、到货和出入库操作轨迹',
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE `owner_warehouse`
  MODIFY COLUMN `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '货主与实仓关联主键ID，供虚仓owner_warehouse_id引用',
  MODIFY COLUMN `company_code` varchar(255) NOT NULL COMMENT '所属公司编码，货主、实仓及引用虚仓必须属于同一公司',
  MODIFY COLUMN `owner_id` int(11) NOT NULL COMMENT '货主ID，关联owner_info.id',
  MODIFY COLUMN `real_store_id` int(11) NOT NULL COMMENT '实体仓库ID，关联wms_real_store_info.id',
  MODIFY COLUMN `status` tinyint(4) NOT NULL DEFAULT '2' COMMENT '关联启用状态：1停用，2启用；业务可用还需货主和实仓启用',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联创建时间',
  MODIFY COLUMN `modify_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '关联最近修改时间',
  COMMENT='货主与实体仓库多对多关联表，维护关联状态，作为虚仓固定归属依据',
  ALGORITHM=INPLACE,
  LOCK=NONE;
