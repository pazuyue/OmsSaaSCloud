CREATE TABLE IF NOT EXISTS oms_refund_return_auto_task (
  company_code VARCHAR(50) NOT NULL,
  refund_id BIGINT NOT NULL,
  platform_version BIGINT NOT NULL,
  policy_revision BIGINT NOT NULL,
  policy JSON NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  message VARCHAR(500) NOT NULL DEFAULT '',
  plan_id BIGINT NULL,
  next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_token VARCHAR(32) NULL,
  lease_until DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(company_code,refund_id),
  KEY due(company_code,state,next_attempt,refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
