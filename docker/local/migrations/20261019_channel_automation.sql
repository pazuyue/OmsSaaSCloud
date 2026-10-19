CREATE TABLE IF NOT EXISTS channel_product_policy (
 company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,
 auto_pull TINYINT NOT NULL DEFAULT 1,auto_bind TINYINT NOT NULL DEFAULT 1,rematch_enabled TINYINT NOT NULL DEFAULT 1,
 interval_minutes INT NOT NULL DEFAULT 30,revision BIGINT NOT NULL DEFAULT 1,next_run_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_success_at DATETIME NULL,last_attempt_at DATETIME NULL,last_error VARCHAR(1000) NOT NULL DEFAULT '',
 updated_by VARCHAR(64) NOT NULL DEFAULT '',update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,channel_id),KEY due(company_code,auto_pull,next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_company_policy (
 company_code VARCHAR(50) PRIMARY KEY,summary_days INT NOT NULL DEFAULT 90,payload_days INT NOT NULL DEFAULT 14,
 revision BIGINT NOT NULL DEFAULT 1,updated_by VARCHAR(64) NOT NULL DEFAULT '',update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS channel_platform_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,channel_id BIGINT NOT NULL,platform_shop_id VARCHAR(32) NOT NULL,
 item_id VARCHAR(32) NOT NULL,title VARCHAR(500) NOT NULL,pic_url VARCHAR(1000) NOT NULL DEFAULT '',listing_state VARCHAR(24) NOT NULL,
 platform_modified VARCHAR(32) NOT NULL DEFAULT '',last_pull_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,last_task_id BIGINT NULL,
 UNIQUE KEY item_identity(company_code,channel_id,item_id),KEY company_page(company_code,id),KEY shop_page(company_code,channel_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
