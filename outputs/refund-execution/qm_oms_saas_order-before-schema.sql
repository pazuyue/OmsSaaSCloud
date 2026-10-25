
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `oms_refund`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `oms_refund` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `company_code` varchar(50) NOT NULL,
  `refund_sn` varchar(64) NOT NULL,
  `platform_refund_id` bigint(20) NOT NULL,
  `channel_id` bigint(20) NOT NULL,
  `refund_id` varchar(64) NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `original_platform_order_id` bigint(20) NOT NULL,
  `platform_tid` varchar(64) NOT NULL,
  `platform_oid` varchar(64) NOT NULL,
  `state` varchar(24) NOT NULL DEFAULT 'NEEDS_REVIEW',
  `review_state` varchar(24) NOT NULL DEFAULT 'PENDING',
  `purpose` varchar(24) NOT NULL DEFAULT 'UNKNOWN',
  `goods_action` varchar(24) NOT NULL DEFAULT 'UNKNOWN',
  `goods_state` varchar(24) NOT NULL DEFAULT 'WAIT_REVIEW',
  `funds_state` varchar(24) NOT NULL DEFAULT 'NONE',
  `approved_version` bigint(20) DEFAULT NULL,
  `approved_hash` char(64) DEFAULT NULL,
  `approved_snapshot` json DEFAULT NULL,
  `refund_fee` decimal(20,2) NOT NULL,
  `cancelled_quantity` bigint(20) NOT NULL DEFAULT '0',
  `cancel_line_id` bigint(20) DEFAULT NULL,
  `cancel_quantity` bigint(20) NOT NULL DEFAULT '0',
  `cancel_command` json DEFAULT NULL,
  `cutoff_id` bigint(20) DEFAULT NULL,
  `review_reason` varchar(500) NOT NULL DEFAULT '',
  `review_evidence` varchar(1000) NOT NULL DEFAULT '',
  `review_by` varchar(64) NOT NULL DEFAULT '',
  `reviewed_at` datetime DEFAULT NULL,
  `problem_code` varchar(64) NOT NULL DEFAULT '',
  `problem_message` varchar(500) NOT NULL DEFAULT '',
  `control_version` bigint(20) NOT NULL DEFAULT '1',
  `next_attempt` datetime DEFAULT NULL,
  `lease_token` varchar(32) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `shared_return` tinyint(4) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `sn_scope` (`company_code`,`refund_sn`),
  UNIQUE KEY `source_scope` (`company_code`,`platform_refund_id`),
  KEY `work_scope` (`company_code`,`state`,`id`),
  KEY `original_scope` (`company_code`,`order_id`,`platform_oid`,`id`),
  KEY `platform_number` (`company_code`,`refund_id`,`id`),
  KEY `due` (`company_code`,`next_attempt`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=89 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `oms_refund_operation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `oms_refund_operation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `company_code` varchar(50) NOT NULL,
  `refund_id` bigint(20) NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `action` varchar(24) NOT NULL,
  `state` varchar(24) NOT NULL,
  `source_version` bigint(20) NOT NULL,
  `platform_version` varchar(64) NOT NULL,
  `fee` decimal(20,2) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `actor` varchar(64) NOT NULL,
  `error_code` varchar(128) NOT NULL DEFAULT '',
  `result_message` varchar(500) NOT NULL DEFAULT '',
  `challenge_until` datetime DEFAULT NULL,
  `next_attempt` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `request_scope` (`company_code`,`request_id`),
  KEY `parent` (`company_code`,`refund_id`,`id`),
  KEY `due` (`company_code`,`state`,`next_attempt`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `oms_platform_refund`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `oms_platform_refund` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `company_code` varchar(50) NOT NULL,
  `channel_id` bigint(20) NOT NULL,
  `refund_id` varchar(64) NOT NULL,
  `platform_tid` varchar(64) NOT NULL DEFAULT '',
  `platform_oid` varchar(64) NOT NULL DEFAULT '',
  `seller_nick` varchar(200) NOT NULL,
  `platform_status` varchar(64) NOT NULL DEFAULT '',
  `dispute_type` varchar(32) NOT NULL DEFAULT '',
  `source_phase` varchar(24) NOT NULL DEFAULT 'UNKNOWN',
  `special_type` varchar(64) NOT NULL DEFAULT '',
  `refund_fee` decimal(20,2) NOT NULL DEFAULT '0.00',
  `platform_quantity` bigint(20) NOT NULL DEFAULT '0',
  `reason` varchar(500) NOT NULL DEFAULT '',
  `source_modified` datetime DEFAULT NULL,
  `refund_version` varchar(64) NOT NULL DEFAULT '',
  `jdp_modified` datetime NOT NULL,
  `source_hash` char(64) NOT NULL,
  `payload` mediumtext NOT NULL,
  `source_state` varchar(24) NOT NULL DEFAULT 'VALID',
  `source_error` varchar(500) NOT NULL DEFAULT '',
  `conversion_state` varchar(24) NOT NULL DEFAULT 'WAITING',
  `conversion_error` varchar(500) NOT NULL DEFAULT '',
  `business_refund_id` bigint(20) DEFAULT NULL,
  `version` bigint(20) NOT NULL DEFAULT '1',
  `last_sync` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `semantic_hash` char(64) NOT NULL DEFAULT '',
  `intake_version` bigint(20) NOT NULL DEFAULT '1',
  `conversion_requested` tinyint(4) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `identity_scope` (`company_code`,`channel_id`,`refund_id`),
  KEY `platform_number` (`company_code`,`refund_id`,`id`),
  KEY `original_scope` (`company_code`,`channel_id`,`platform_tid`,`platform_oid`,`id`),
  KEY `convert_due` (`company_code`,`conversion_state`,`id`),
  KEY `document_tid` (`company_code`,`platform_tid`,`channel_id`,`id`),
  KEY `document_modified` (`company_code`,`source_modified`,`id`),
  KEY `document_oid` (`company_code`,`platform_oid`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=90 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `oms_platform_refund_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `oms_platform_refund_snapshot` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `company_code` varchar(50) NOT NULL,
  `platform_refund_id` bigint(20) NOT NULL,
  `payload_hash` char(64) NOT NULL,
  `payload` mediumtext NOT NULL,
  `source_modified` datetime DEFAULT NULL,
  `jdp_modified` datetime NOT NULL,
  `outcome` varchar(24) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `observed` (`company_code`,`platform_refund_id`,`payload_hash`),
  KEY `parent` (`company_code`,`platform_refund_id`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=96 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `oms_order_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `oms_order_line` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  `order_id` bigint(20) NOT NULL COMMENT 'OMS业务订单ID，关联oms_order.id',
  `platform_oid` varchar(64) NOT NULL COMMENT '平台子订单编号，以字符串保存避免精度丢失',
  `sku_sn` varchar(128) NOT NULL COMMENT '内部商品SKU编码',
  `goods_name` varchar(500) NOT NULL COMMENT '商品名称快照',
  `quantity` int(11) NOT NULL COMMENT '业务订单商品购买数量',
  `payment` decimal(18,2) NOT NULL COMMENT '实付金额，单位元',
  `reserved_quantity` int(11) NOT NULL DEFAULT '0' COMMENT '订单行已逻辑预占数量，不代表仓库实物占用',
  `inventory_reason` varchar(500) NOT NULL DEFAULT '' COMMENT '订单行库存预占处理说明',
  `inventory_reason_code` varchar(40) NOT NULL DEFAULT '' COMMENT '预占等待原因代码',
  `cancelled_quantity` bigint(20) NOT NULL DEFAULT '0',
  `business_type` varchar(24) NOT NULL DEFAULT 'SALE',
  `exchange_id` bigint(20) DEFAULT NULL,
  `stock_reference` varchar(64) NOT NULL DEFAULT '',
  `source_line_id` bigint(20) DEFAULT NULL,
  `source_shipment_line_id` bigint(20) DEFAULT NULL,
  `fulfillment_round` int(11) NOT NULL DEFAULT '1',
  `bundle_id` bigint(20) DEFAULT NULL,
  `component_quantity` int(11) NOT NULL DEFAULT '0',
  `stocked_bundle_snapshot` json DEFAULT NULL,
  `gift_plan_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_line` (`order_id`,`platform_oid`,`stock_reference`,`sku_sn`),
  KEY `idx_business_sku` (`company_code`,`sku_sn`,`order_id`),
  KEY `fulfillment_scope` (`company_code`,`order_id`,`business_type`,`stock_reference`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=202 DEFAULT CHARSET=utf8mb4 COMMENT='OMS业务订单明细及逻辑预占结果';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `oms_order_shipment_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `oms_order_shipment_line` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录主键ID',
  `company_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，用于租户隔离',
  `shipment_id` bigint(20) NOT NULL COMMENT '发货单ID，关联订单库oms_order_shipment.id',
  `allocation_id` bigint(20) NOT NULL COMMENT '订单分仓占用回执ID，关联库存库oms_order_warehouse_allocation.id',
  `order_line_id` bigint(20) NOT NULL COMMENT 'OMS业务订单明细ID，关联oms_order_line.id',
  `platform_oid` varchar(64) NOT NULL COMMENT '平台子订单编号，以字符串保存避免精度丢失',
  `sku_sn` varchar(128) NOT NULL COMMENT '内部商品SKU编码',
  `goods_name` varchar(500) NOT NULL COMMENT '商品名称快照',
  `store_code` varchar(64) NOT NULL COMMENT '虚拟仓库编码',
  `batch_id` bigint(20) NOT NULL COMMENT '仓库库存批次ID，关联库存库wms_inventory_batch.id',
  `source_type` varchar(24) NOT NULL COMMENT '库存来源类型：LOCK渠道锁库，PUBLIC_QUOTA普通额度，PUBLIC_EXTRA公共补占',
  `stock_source_id` bigint(20) NOT NULL COMMENT '订单库存来源ID，关联oms_order_stock_source.id',
  `quantity` bigint(20) NOT NULL COMMENT '发货行计划出库数量',
  `shipped_quantity` bigint(20) NOT NULL DEFAULT '0' COMMENT '发货行累计实际出库数量',
  `cancelled_quantity` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `receipt_once` (`company_code`,`allocation_id`),
  KEY `shipment_lines` (`company_code`,`shipment_id`,`id`),
  KEY `shipment_sku` (`company_code`,`sku_sn`,`shipment_id`)
) ENGINE=InnoDB AUTO_INCREMENT=122 DEFAULT CHARSET=utf8mb4 COMMENT='每条占用回执仅归属一个发货单，保留虚仓与批次追溯';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

