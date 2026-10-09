-- Historical balances are deliberately not inferred from aggregate stock.
ALTER TABLE rule_stock_result ADD COLUMN source_tracked TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN occupied_quantity INT NOT NULL DEFAULT 0,
  ADD COLUMN consumed_quantity INT NOT NULL DEFAULT 0,
  ADD COLUMN released_quantity INT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS rule_stock_reservation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  company_code VARCHAR(50) NOT NULL,
  rule_id BIGINT NOT NULL,
  sku_sn VARCHAR(50) NOT NULL,
  channel_id BIGINT NOT NULL,
  batch_id BIGINT NOT NULL,
  original_quantity INT NOT NULL,
  occupied_quantity INT NOT NULL DEFAULT 0,
  consumed_quantity INT NOT NULL DEFAULT 0,
  released_quantity INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_source(rule_id,sku_sn,channel_id,batch_id),
  KEY idx_source_batch(company_code,sku_sn,batch_id),
  KEY idx_source_channel(company_code,sku_sn,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS rule_stock_order_reservation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  company_code VARCHAR(50) NOT NULL,
  rule_id BIGINT NOT NULL,
  sku_sn VARCHAR(50) NOT NULL,
  order_line VARCHAR(100) NOT NULL,
  source_id BIGINT NOT NULL,
  original_quantity INT NOT NULL,
  occupied_quantity INT NOT NULL,
  consumed_quantity INT NOT NULL DEFAULT 0,
  cancelled_quantity INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_order_source(company_code,rule_id,sku_sn,order_line,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS rule_stock_reservation_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  company_code VARCHAR(50) NOT NULL,
  rule_id BIGINT NOT NULL,
  sku_sn VARCHAR(50) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  action VARCHAR(16) NOT NULL,
  order_line VARCHAR(100) NOT NULL,
  channel_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  journal_request VARCHAR(64) NOT NULL DEFAULT '',
  operator_name VARCHAR(255) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_request(company_code,rule_id,sku_sn,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
