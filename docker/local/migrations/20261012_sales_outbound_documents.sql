CREATE TABLE IF NOT EXISTS wms_sales_outbound_lock (
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, shipment_id BIGINT NOT NULL,
 PRIMARY KEY(company_code,shipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wms_sales_outbound_document (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 shipment_id BIGINT NOT NULL, shipment_sn VARCHAR(64) NOT NULL,
 store_code VARCHAR(255) NOT NULL, ticket_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL, platform_tid VARCHAR(100) NOT NULL,
 channel_id BIGINT NOT NULL, channel_name VARCHAR(255) NOT NULL,
 source_version BIGINT NOT NULL, source_hash VARCHAR(64) NOT NULL,
 source_status VARCHAR(32) NOT NULL, dispatch_state VARCHAR(32) NOT NULL,
 external_order VARCHAR(100) NOT NULL DEFAULT '', warehouse_status VARCHAR(40) NOT NULL DEFAULT '',
 last_error VARCHAR(1000) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY sales_source(company_code,shipment_id,store_code),
 UNIQUE KEY sales_ticket(company_code,ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wms_sales_outbound_movement (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, ticket_id BIGINT NOT NULL,
 ticket_line_id BIGINT NOT NULL, source_line_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, source_mode VARCHAR(12) NOT NULL,
 quantity INT NOT NULL, batch_id BIGINT NOT NULL,
 operator_name VARCHAR(64) NOT NULL, outbound_time DATETIME NOT NULL,
 UNIQUE KEY sales_movement(company_code,request_id,source_line_id),
 KEY sales_ticket_time(company_code,ticket_id,outbound_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
