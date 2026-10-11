-- Inventory service owns both demand and logical reservations.
CREATE TABLE IF NOT EXISTS oms_order_stock_demand (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 channel_id BIGINT NOT NULL,
 platform_tid VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 platform_oid VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 sku_sn VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 owner_code VARCHAR(64) NOT NULL,
 store_codes TEXT NOT NULL COMMENT '有效库存范围的固定快照JSON',
 allow_public_stock TINYINT NOT NULL DEFAULT 0,
 demand_quantity INT NOT NULL,
 reserved_quantity INT NOT NULL DEFAULT 0 COMMENT '公司和渠道逻辑预占，不代表仓库预占',
 status VARCHAR(24) NOT NULL DEFAULT 'WAIT_RESERVE',
 reason VARCHAR(500) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_order_demand(company_code,channel_id,platform_tid,platform_oid),
 KEY idx_demand_sku(company_code,sku_sn,status,id),
 KEY idx_demand_retry(company_code,channel_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单行销售需求，拉单解析登记，转单更新逻辑预占';

CREATE TABLE IF NOT EXISTS oms_order_stock_source (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 demand_id BIGINT NOT NULL,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 sku_sn VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 store_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 source_type VARCHAR(24) NOT NULL COMMENT 'LOCK渠道锁库/PUBLIC_QUOTA普通额度/PUBLIC_EXTRA公共补占',
 source_id BIGINT NOT NULL DEFAULT 0 COMMENT 'LOCK引用rule_stock_reservation，其余为0',
 quantity INT NOT NULL,
 reserved TINYINT NOT NULL DEFAULT 0 COMMENT '0待占保护，1已逻辑预占',
 KEY idx_demand_source(demand_id),
 KEY idx_source_pool(company_code,sku_sn,store_code,source_type),
 KEY idx_source_lock(source_id,source_type,reserved)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求的库存来源与额度归属，不变更仓库库存';

CREATE TABLE IF NOT EXISTS oms_order_stock_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 demand_id BIGINT NOT NULL,
 action VARCHAR(24) NOT NULL,
 quantity INT NOT NULL,
 message VARCHAR(500) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_demand_event(demand_id,action),
 KEY idx_stock_event(company_code,demand_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单库存登记和预占幂等流水';
