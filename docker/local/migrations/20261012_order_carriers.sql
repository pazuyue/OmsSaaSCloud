CREATE TABLE IF NOT EXISTS oms_order_carrier (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 carrier_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 carrier_name VARCHAR(100) NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 1,
 services_json TEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 updated_by VARCHAR(100) NOT NULL,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY carrier_identity(company_code,carrier_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司快递资料及货主实体仓服务范围';

CREATE TABLE IF NOT EXISTS oms_order_carrier_config (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 channel_id BIGINT NOT NULL,
 policy_json TEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 updated_by VARCHAR(100) NOT NULL,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY shop_carrier_policy(company_code,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺允许快递顺序及实体仓省份优先规则';

CREATE TABLE IF NOT EXISTS oms_order_shipment_log (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 shipment_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL,
 result VARCHAR(24) NOT NULL,
 message VARCHAR(500) NOT NULL,
 operator_name VARCHAR(100) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY shipment_history(company_code,shipment_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货单快递匹配及调整记录';
