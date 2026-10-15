CREATE TABLE IF NOT EXISTS sys_company (
 company_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '不可变公司编码',
 company_name VARCHAR(100) NOT NULL COMMENT '公司名称',
 contact_name VARCHAR(50) NOT NULL DEFAULT '' COMMENT '联系人',
 contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话',
 remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT未启用 ENABLED已启用 DISABLED已停用',
 ever_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否曾启用，启用后禁止普通换库',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
 route_revision BIGINT NOT NULL DEFAULT 1 COMMENT '服务映射草稿版本',
 release_id VARCHAR(32) NOT NULL DEFAULT '' COMMENT '当前已发布路由版本，空为既有接入',
 release_route_revision BIGINT NOT NULL DEFAULT 0 COMMENT '已发布的草稿版本',
 last_check LONGTEXT NULL COMMENT '脱敏服务校验结果JSON',
 last_detect LONGTEXT NULL COMMENT '脱敏实际生效检测JSON',
 runtime_ready TINYINT NOT NULL DEFAULT 0 COMMENT '列表生效摘要，避免分页读取完整实例JSON',
 last_detect_at BIGINT NULL COMMENT '最近检测时间戳毫秒',
 created_by VARCHAR(64) NOT NULL COMMENT '创建人',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 updated_by VARCHAR(64) NOT NULL COMMENT '修改人',
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
 KEY idx_company_status_updated(status,updated_at,company_code),
 KEY idx_company_updated(updated_at,company_code),
 KEY idx_company_name(company_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台公司入驻主档';
CREATE TABLE IF NOT EXISTS sys_company_service_route (
 company_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '公司编码',
 service_key VARCHAR(32) NOT NULL COMMENT '固定业务服务标识',
 datasource_alias VARCHAR(64) NOT NULL COMMENT '服务内已准备的物理连接名称',
 PRIMARY KEY(company_code,service_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司五服务路由草稿，不保存连接密码';
CREATE TABLE IF NOT EXISTS sys_company_release (
 id VARCHAR(32) PRIMARY KEY COMMENT '发布幂等ID及运行版本',
 company_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '公司编码',
 route_revision BIGINT NOT NULL COMMENT '对应服务配置版本',
 state VARCHAR(24) NOT NULL COMMENT 'PUBLISHING PUBLISHED ROLLED_BACK ROLLBACK_FAILED',
 snapshot_json LONGTEXT NOT NULL COMMENT '目标映射及逐服务旧映射，不含数据库凭证',
 results_json LONGTEXT NULL COMMENT '逐服务发布与恢复结果',
 created_by VARCHAR(64) NOT NULL COMMENT '发布人',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近处理时间',
 KEY idx_company_release(company_code,created_at),
 KEY idx_release_state(state,company_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司路由发布与恢复记录';
CREATE TABLE IF NOT EXISTS sys_company_publish_lock (
 id INT PRIMARY KEY COMMENT '固定全局发布锁',
 lease_token VARCHAR(32) NOT NULL DEFAULT '' COMMENT '当前发布ID',
 lease_until DATETIME NULL COMMENT '发布租约截止时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨服务配置发布互斥';
INSERT IGNORE INTO sys_company_publish_lock(id) VALUES(1);
CREATE TABLE IF NOT EXISTS sys_company_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
 company_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '公司编码',
 action VARCHAR(32) NOT NULL COMMENT '操作类型',
 result VARCHAR(24) NOT NULL COMMENT '操作结果',
 message VARCHAR(1000) NOT NULL COMMENT '脱敏说明',
 operator_name VARCHAR(64) NOT NULL COMMENT '操作人',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
 KEY idx_company_event(company_code,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司入驻操作记录';
