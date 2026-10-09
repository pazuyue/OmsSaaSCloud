-- Additive migration. Existing rules and stock quantities are preserved.
ALTER TABLE rule_stock_info ADD COLUMN revision INT NOT NULL DEFAULT 1,
  ADD COLUMN run_action VARCHAR(16) NOT NULL DEFAULT 'ALLOCATE',
  ADD INDEX idx_rule_company_page(company_code,id);
ALTER TABLE rule_stock_channel_info ADD COLUMN priority INT NOT NULL DEFAULT 0,
  ADD INDEX idx_rule_channel(rule_id,priority,id);
ALTER TABLE rule_stock_store_code_info ADD INDEX idx_rule_store(rule_id,store_code);
ALTER TABLE rule_stock_goods_info ADD INDEX idx_rule_goods(rule_id,sku_sn(50));
CREATE TABLE IF NOT EXISTS rule_stock_result (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rule_id BIGINT NOT NULL,
  company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  sku_sn VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  release_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
  allocated_quantity INT NOT NULL DEFAULT 0,
  detail_json MEDIUMTEXT NULL,
  error_message VARCHAR(500) NOT NULL DEFAULT '',
  release_error VARCHAR(500) NOT NULL DEFAULT '',
  attempts INT NOT NULL DEFAULT 0,
  operator_name VARCHAR(255) NOT NULL DEFAULT '',
  release_operator VARCHAR(255) NOT NULL DEFAULT '',
  modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_rule_sku(rule_id,sku_sn),
  KEY idx_rule_pending(rule_id,status,sku_sn),
  KEY idx_rule_release(rule_id,release_status,sku_sn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
