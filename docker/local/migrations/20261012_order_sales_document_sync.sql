CREATE TABLE IF NOT EXISTS oms_order_sales_document_sync (
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 shipment_id BIGINT NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 payload_hash VARCHAR(64) NOT NULL DEFAULT '', payload_json MEDIUMTEXT,
 synced_revision BIGINT NOT NULL DEFAULT 0,
 last_error VARCHAR(1000) NOT NULL DEFAULT '',
 next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,shipment_id), KEY sales_sync_due(next_attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
