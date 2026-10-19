CREATE TABLE IF NOT EXISTS channel_stock_sync_group (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,
 sku_sn VARCHAR(128) NOT NULL,safety_stock BIGINT NOT NULL DEFAULT 0,quantity_cap BIGINT NULL,
 enabled TINYINT NOT NULL DEFAULT 0,revision BIGINT NOT NULL DEFAULT 1,
 requested_by VARCHAR(64) NOT NULL DEFAULT '',state VARCHAR(24) NOT NULL DEFAULT 'NEW',last_error VARCHAR(1000) NOT NULL DEFAULT '',
 available_quantity BIGINT NULL,target_quantity BIGINT NULL,retained_quantity BIGINT NULL,
 next_run_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,last_run_at DATETIME NULL,
 lease_token VARCHAR(64) NULL,lease_until DATETIME NULL,
 created_by VARCHAR(64) NOT NULL,update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY shop_sku(company_code,channel_id,sku_sn),KEY scan_due(company_code,enabled,next_run_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_stock_sync_link (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,group_id BIGINT NOT NULL,
 item_id VARCHAR(32) NOT NULL,sku_id VARCHAR(32) NOT NULL DEFAULT '0',name VARCHAR(100) NOT NULL,
 weight INT NOT NULL,quantity_cap BIGINT NULL,enabled TINYINT NOT NULL DEFAULT 1,
 observed_quantity BIGINT NULL,observed_at DATETIME NULL,last_submitted BIGINT NULL,submitted_at DATETIME NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'NEW',pending_quantity BIGINT NULL,pending_at DATETIME NULL,
 last_error VARCHAR(1000) NOT NULL DEFAULT '',
 UNIQUE KEY platform_target(company_code,channel_id,item_id,sku_id),KEY group_links(company_code,group_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_stock_sync_run (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,group_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL,revision BIGINT NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
 trigger_source VARCHAR(24) NOT NULL,operator_name VARCHAR(64) NOT NULL,
 snapshot_json JSON NULL,last_error VARCHAR(1000) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,finished_at DATETIME NULL,
 UNIQUE KEY request_identity(company_code,request_id),KEY group_runs(company_code,group_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_stock_sync_sim (
 company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,item_id VARCHAR(32) NOT NULL,sku_id VARCHAR(32) NOT NULL,
 quantity BIGINT NOT NULL,inventory_mode INT NOT NULL DEFAULT 1,
 pending_trades JSON NULL,fail_write VARCHAR(24) NOT NULL DEFAULT '',
 PRIMARY KEY(company_code,channel_id,item_id,sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
