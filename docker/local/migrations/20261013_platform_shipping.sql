CREATE TABLE IF NOT EXISTS oms_order_platform_shipping_config (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
 mode VARCHAR(16) NOT NULL DEFAULT 'OFF', carrier_mapping MEDIUMTEXT NOT NULL,
 enabled_at DATETIME NULL, control_version BIGINT NOT NULL DEFAULT 0,
 updated_by VARCHAR(64) NOT NULL DEFAULT '', update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_platform_shipping_config(company_code,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_order_platform_shipping_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL, shipment_id BIGINT NOT NULL, package_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, mode VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 quantity BIGINT NOT NULL, payload_json MEDIUMTEXT NOT NULL, payload_hash CHAR(64) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, cycle_attempts INT NOT NULL DEFAULT 0, query_attempts INT NOT NULL DEFAULT 0,
 next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, lease_until DATETIME NULL, lease_token VARCHAR(32) NOT NULL DEFAULT '',
 last_error VARCHAR(500) NOT NULL DEFAULT '', platform_receipt VARCHAR(100) NOT NULL DEFAULT '',
 created_by VARCHAR(64) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 success_at DATETIME NULL, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_platform_shipping_package(company_code,package_id), UNIQUE KEY uk_platform_shipping_request(company_code,request_id),
 KEY idx_platform_shipping_due(company_code,state,next_attempt), KEY idx_platform_shipping_order(company_code,order_id),
 KEY idx_platform_shipping_shipment(company_code,shipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_order_platform_shipping_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, task_id BIGINT NOT NULL,
 action VARCHAR(24) NOT NULL, result VARCHAR(16) NOT NULL, message VARCHAR(500) NOT NULL,
 operator_name VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_platform_shipping_log(company_code,task_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Durable mock-platform receipts, deliberately separate from task status. No external TOP call.
CREATE TABLE IF NOT EXISTS oms_order_platform_shipping_sim_receipt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, request_id VARCHAR(64) NOT NULL,
 payload_hash CHAR(64) NOT NULL, receipt_code VARCHAR(100) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_platform_shipping_sim(company_code,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
