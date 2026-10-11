CREATE TABLE IF NOT EXISTS oms_order_push_source (
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 source_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 source_name VARCHAR(100) NOT NULL COMMENT '页面展示名称',
 host VARCHAR(253) NOT NULL,
 port INT NOT NULL DEFAULT 3306,
 database_name VARCHAR(64) NOT NULL,
 username VARCHAR(64) NOT NULL,
 password_cipher TEXT NOT NULL COMMENT 'AES-GCM加密密码，不向前端返回',
 enabled TINYINT NOT NULL DEFAULT 0,
 revision INT NOT NULL DEFAULT 1,
 last_test_status VARCHAR(16) NOT NULL DEFAULT 'UNTESTED',
 last_test_time DATETIME NULL,
 last_test_message VARCHAR(200) NOT NULL DEFAULT '尚未测试',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司推送库连接配置，管理员维护，源库只读';

-- Existing fields retain their data; they are unset before warehouse allocation.
ALTER TABLE oms_order_pull_config MODIFY owner_code VARCHAR(64) NULL DEFAULT NULL COMMENT '拉单不设置货主', MODIFY store_codes TEXT NULL COMMENT '拉单不设置发货虚仓';
ALTER TABLE oms_order MODIFY owner_code VARCHAR(64) NULL DEFAULT NULL COMMENT '分仓流程确定货主，一期未分仓为空';
