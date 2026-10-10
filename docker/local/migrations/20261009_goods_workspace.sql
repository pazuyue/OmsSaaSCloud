CREATE TABLE IF NOT EXISTS goods_master_lock (
  company_code VARCHAR(50) COLLATE utf8mb4_bin PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS goods_import_batch (
  import_batch VARCHAR(36) COLLATE utf8mb4_bin PRIMARY KEY,
  company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PREVIEW',
  total_rows INT NOT NULL,
  error_rows INT NOT NULL,
  create_user VARCHAR(100),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirm_user VARCHAR(100),
  confirm_time DATETIME,
  KEY idx_goods_import_company(company_code,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS goods_import_row (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  import_batch VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
  company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  row_num INT NOT NULL,
  payload LONGTEXT NOT NULL,
  notes TEXT NOT NULL,
  UNIQUE KEY uk_goods_import_row(company_code,import_batch,row_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Apply once to each company's commodity datasource before starting the new goods service.
ALTER TABLE goods_size ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '尺码业务排序，越小越靠前';
