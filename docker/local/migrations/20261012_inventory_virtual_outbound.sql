CREATE TABLE IF NOT EXISTS oms_order_virtual_outbound (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 shipment_sn VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 channel_id BIGINT NOT NULL,
 platform_tid VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 receipt_json MEDIUMTEXT NOT NULL,
 quantity BIGINT NOT NULL,
 operator_name VARCHAR(100) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY virtual_request(company_code,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟出库幂等回执，与库存扣减同一事务';
