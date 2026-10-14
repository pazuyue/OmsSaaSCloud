CREATE TABLE IF NOT EXISTS wms_outbound_receipt_review (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 task_id BIGINT NOT NULL,
 shipment_id BIGINT NOT NULL,
 shipment_sn VARCHAR(64) NOT NULL,
 message_id VARCHAR(128) NOT NULL,
 receipt_hash VARCHAR(64) NOT NULL,
 receipt_json MEDIUMTEXT NOT NULL,
 conflict_code VARCHAR(32) NOT NULL,
 conflict_message VARCHAR(1000) NOT NULL,
 source_log_id BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'WAIT_REVIEW',
 control_version BIGINT NOT NULL DEFAULT 0,
 event_id BIGINT NULL,
 decision VARCHAR(32) NOT NULL DEFAULT '',
 reason VARCHAR(200) NOT NULL DEFAULT '',
 operator_name VARCHAR(100) NOT NULL DEFAULT '',
 decided_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY review_receipt(company_code,task_id,receipt_hash),
 KEY review_shipment(company_code,shipment_id,state,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wms_outbound_waybill_lock (
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 logistics_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 express_code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 PRIMARY KEY(company_code,logistics_code,express_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
