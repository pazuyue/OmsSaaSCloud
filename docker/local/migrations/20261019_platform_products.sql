CREATE TABLE IF NOT EXISTS channel_platform_product (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,
 platform_shop_id VARCHAR(32) NOT NULL,item_id VARCHAR(32) NOT NULL,sku_id VARCHAR(32) NOT NULL,
 title VARCHAR(500) NOT NULL,spec_name VARCHAR(500) NOT NULL DEFAULT '',outer_id VARCHAR(128) NOT NULL DEFAULT '',
 properties VARCHAR(1000) NOT NULL DEFAULT '',listing_state VARCHAR(24) NOT NULL,platform_quantity BIGINT NOT NULL,
 present TINYINT NOT NULL DEFAULT 1,binding_state VARCHAR(24) NOT NULL DEFAULT 'UNBOUND',
 oms_sku_sn VARCHAR(128) NULL,oms_goods_name VARCHAR(255) NOT NULL DEFAULT '',goods_type VARCHAR(24) NOT NULL DEFAULT '',
 binding_revision BIGINT NOT NULL DEFAULT 1,identity_hash CHAR(64) NOT NULL,
 last_pull_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,bound_by VARCHAR(64) NOT NULL DEFAULT '',bound_at DATETIME NULL,
 UNIQUE KEY platform_identity(company_code,channel_id,item_id,sku_id),KEY binding_lookup(company_code,channel_id,oms_sku_sn,binding_state,id),
 KEY shop_page(company_code,channel_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_product_binding_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,product_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL,from_sku VARCHAR(128) NULL,to_sku VARCHAR(128) NULL,
 binding_revision BIGINT NOT NULL,reason VARCHAR(500) NOT NULL,actor VARCHAR(64) NOT NULL,
 snapshot_json JSON NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY product_events(company_code,product_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_product_pull (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,
 platform_shop_id VARCHAR(32) NOT NULL,mode VARCHAR(16) NOT NULL,phase VARCHAR(24) NOT NULL,
 page_no INT NOT NULL DEFAULT 1,state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 lease_token VARCHAR(64) NULL,lease_until DATETIME NULL,last_error VARCHAR(1000) NOT NULL DEFAULT '',
 operator_name VARCHAR(64) NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,finished_at DATETIME NULL,
 KEY task_scan(company_code,state,id),KEY shop_tasks(company_code,channel_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_product_pull_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,task_id BIGINT NOT NULL,
 item_id VARCHAR(32) NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'PENDING',last_error VARCHAR(1000) NOT NULL DEFAULT '',
 UNIQUE KEY item_task(company_code,task_id,item_id),KEY task_items(company_code,task_id,state,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_product_sim (
 company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,item_id VARCHAR(32) NOT NULL,
 payload JSON NOT NULL,PRIMARY KEY(company_code,channel_id,item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
