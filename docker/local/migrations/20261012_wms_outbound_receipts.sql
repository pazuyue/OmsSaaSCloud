CREATE TABLE IF NOT EXISTS wms_outbound_receipt_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 task_id BIGINT NOT NULL,
 shipment_sn VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 message_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 payload_json MEDIUMTEXT NOT NULL,
 quantity BIGINT NOT NULL,
 final_confirmation TINYINT NOT NULL DEFAULT 0,
 post_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 post_error VARCHAR(1000) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 posted_at DATETIME NULL,
 UNIQUE KEY receipt_message(company_code,task_id,message_id),
 KEY receipt_pending(company_code,post_state,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wms_outbound_receipt_package (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 task_id BIGINT NOT NULL,
 event_id BIGINT NOT NULL,
 package_code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 express_code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 logistics_code VARCHAR(64) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 lines_json MEDIUMTEXT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY receipt_package(company_code,task_id,package_code),
 UNIQUE KEY receipt_waybill(company_code,logistics_code,express_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
