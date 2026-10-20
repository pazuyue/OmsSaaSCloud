CREATE TABLE IF NOT EXISTS oms_gift_group (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL,
 name VARCHAR(100) NOT NULL, priority INT NOT NULL, enabled TINYINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 1, policy JSON NOT NULL, updated_by VARCHAR(64) NOT NULL,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY scope_order(company_code,enabled,priority,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_gift_group_version (
 company_code VARCHAR(50) NOT NULL, group_id BIGINT NOT NULL, revision BIGINT NOT NULL,
 snapshot JSON NOT NULL, actor VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,group_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_gift_plan (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL,
 platform_order_id BIGINT NOT NULL, order_id BIGINT NULL, channel_id BIGINT NOT NULL,
 snapshot JSON NOT NULL, basis_json JSON NOT NULL, initial_json JSON NOT NULL,
 assessment JSON NULL, assessment_hash CHAR(64) NOT NULL DEFAULT '',
 control_version BIGINT NOT NULL DEFAULT 1, dirty TINYINT NOT NULL DEFAULT 1, next_check DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error VARCHAR(500) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY source_plan(company_code,platform_order_id), UNIQUE KEY order_plan(company_code,order_id),
 KEY scan_due(company_code,next_check,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_gift_resolution (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL,
 plan_id BIGINT NOT NULL, order_line_id BIGINT NOT NULL, quantity BIGINT NOT NULL,
 action VARCHAR(20) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 request_id VARCHAR(64) NOT NULL, command_json JSON NULL,
 reason VARCHAR(500) NOT NULL, evidence VARCHAR(1000) NOT NULL, actor VARCHAR(64) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY request_identity(company_code,request_id), KEY line_scope(company_code,plan_id,order_line_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_gift_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, plan_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL, message VARCHAR(1000) NOT NULL, actor VARCHAR(64) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY plan_events(company_code,plan_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
