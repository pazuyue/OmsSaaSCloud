-- Run with the inventory schema selected. Back up before applying.
ALTER TABLE wms_inventory MODIFY company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  MODIFY sku_sn VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  MODIFY store_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  DROP INDEX sku_sn_index,
  ADD UNIQUE KEY uk_inventory_company_sku_store (company_code, sku_sn, store_code);
ALTER TABLE wms_inventory_batch MODIFY company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  MODIFY sku_sn VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  MODIFY store_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  MODIFY batch_code VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  DROP INDEX sku_index,
  ADD UNIQUE KEY uk_batch_company_sku_store (company_code, sku_sn, store_code, batch_code);
ALTER TABLE oms_inventory DROP INDEX sku_sn,
  ADD UNIQUE KEY uk_oms_company_sku (company_code, sku_sn);

CREATE TABLE IF NOT EXISTS wms_inventory_change_history (
  log_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  company_code VARCHAR(50) NOT NULL,
  store_code VARCHAR(255) NOT NULL,
  sku_sn VARCHAR(255) NOT NULL,
  batch_id BIGINT NULL,
  batch_code VARCHAR(255) NULL,
  inventory_type VARCHAR(2) NULL,
  operation_type VARCHAR(16) NOT NULL,
  request_id VARCHAR(64) NULL,
  relation_sn VARCHAR(128) NULL,
  change_quantity DECIMAL(18,0) NOT NULL DEFAULT 0,
  change_reason VARCHAR(255) NULL,
  operator_id BIGINT NULL,
  operator_name VARCHAR(64) NULL,
  old_zp_actual_number INT NULL, new_zp_actual_number INT NULL,
  old_zp_available_number INT NULL, new_zp_available_number INT NULL,
  old_zp_lock_number INT NULL, new_zp_lock_number INT NULL,
  old_cp_actual_number INT NULL, new_cp_actual_number INT NULL,
  old_cp_available_number INT NULL, new_cp_available_number INT NULL,
  old_cp_lock_number INT NULL, new_cp_lock_number INT NULL,
  operation_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_request_batch (company_code, sku_sn, request_id, batch_id, operation_type),
  KEY idx_inventory_history (company_code, sku_sn, store_code, log_id),
  KEY idx_inventory_relation (company_code, sku_sn, relation_sn, operation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
