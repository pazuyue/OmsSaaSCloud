CREATE TABLE IF NOT EXISTS wms_connection (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '对接配置ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 name VARCHAR(100) NOT NULL COMMENT '配置名称',
 provider VARCHAR(32) NOT NULL COMMENT '协议适配器：QIMEN、JD_HUFU',
 api_version VARCHAR(8) NOT NULL COMMENT '协议版本，按平台开通版本配置',
 environment VARCHAR(16) NOT NULL COMMENT 'TEST测试、PRODUCTION正式',
 endpoint VARCHAR(500) NOT NULL COMMENT '仓库接口地址',
 app_key VARCHAR(200) NOT NULL COMMENT '应用标识',
 customer_id VARCHAR(200) NOT NULL DEFAULT '' COMMENT '平台客户标识',
 secret_cipher TEXT NOT NULL COMMENT 'AES-GCM加密的应用密钥',
 callback_key VARCHAR(64) NOT NULL COMMENT '回调路由随机标识，不作为鉴权凭证',
 enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许新任务下发',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
 PRIMARY KEY(id), UNIQUE KEY uk_connection_name(company_code,name), UNIQUE KEY uk_callback(callback_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库平台连接配置，供虚仓复用';

CREATE TABLE IF NOT EXISTS wms_inbound_task (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '真实采购入库任务ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 ticket_id BIGINT NOT NULL COMMENT '入库执行单ID',
 ticket_sn VARCHAR(100) NOT NULL COMMENT 'OMS入库单号，对外请求稳定幂等标识',
 connection_id BIGINT NOT NULL COMMENT '审核时绑定的对接配置ID',
 external_warehouse VARCHAR(100) NOT NULL COMMENT '审核时外部仓库编码',
 external_owner VARCHAR(100) NOT NULL COMMENT '审核时外部货主编码',
 dispatch_state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待下发、SENDING下发中、ACCEPTED已受理、FAILED失败、UNKNOWN待确认、CANCEL_PENDING取消中、CANCELED已取消',
 receipt_state VARCHAR(24) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING待收货、PARTIAL部分收货、COMPLETE收货完成',
 external_order VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'WMS入库单号',
 attempts INT NOT NULL DEFAULT 0 COMMENT '下发尝试次数',
 last_error VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '最近失败原因',
 next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次任务执行时间',
 lease_until DATETIME NULL COMMENT '执行租约到期时间，防止多个工作节点重复取任务',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
 PRIMARY KEY(id), UNIQUE KEY uk_task_ticket(company_code,ticket_id), UNIQUE KEY uk_task_sn(company_code,ticket_sn), KEY idx_pending(dispatch_state,next_attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='真实入库下发任务和执行配置快照';

CREATE TABLE IF NOT EXISTS wms_receipt_event (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '收货事件ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 task_id BIGINT NOT NULL COMMENT '真实入库任务ID',
 message_id VARCHAR(128) NOT NULL COMMENT '平台消息幂等标识',
 payload_hash VARCHAR(64) NOT NULL COMMENT '标准化事件摘要，防止相同消息ID变更内容',
 final_receipt TINYINT NOT NULL COMMENT '是否最终收货确认',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
 PRIMARY KEY(id), UNIQUE KEY uk_receipt_message(task_id,message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WMS收货事件去重记录';

CREATE TABLE IF NOT EXISTS wms_receipt_line (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '收货明细ID，同时作为库存入账稳定幂等键',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 event_id BIGINT NOT NULL COMMENT '所属收货事件',
 task_id BIGINT NOT NULL COMMENT '真实入库任务ID',
 ticket_line_id BIGINT NOT NULL COMMENT '原入库计划明细ID',
 sku_sn VARCHAR(100) NOT NULL COMMENT 'SKU',
 batch_code VARCHAR(100) NOT NULL COMMENT 'WMS实际收货批次',
 good_qty INT NOT NULL COMMENT '本次正品增量',
 bad_qty INT NOT NULL COMMENT '本次次品增量',
 posted TINYINT NOT NULL DEFAULT 0 COMMENT '0待入账、1成功、2失败',
 error_info VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '入账失败原因',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 PRIMARY KEY(id), KEY idx_task_post(task_id,posted), KEY idx_event(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='真实收货增量及每次库存入账结果';

CREATE TABLE IF NOT EXISTS wms_interaction_log (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '交互日志ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 connection_id BIGINT NOT NULL COMMENT '对接配置ID',
 task_id BIGINT NULL COMMENT '关联任务，无法匹配时为空',
 ticket_sn VARCHAR(100) NOT NULL DEFAULT '' COMMENT '关联OMS入库单号',
 direction VARCHAR(8) NOT NULL COMMENT 'OUT请求仓库、IN仓库回传',
 action VARCHAR(32) NOT NULL COMMENT 'CREATE创建、QUERY查询、CANCEL取消、RECEIPT收货回传',
 request_id VARCHAR(64) NOT NULL COMMENT '本次交互唯一标识',
 request_body MEDIUMTEXT NOT NULL COMMENT '脱敏请求报文',
 response_body MEDIUMTEXT NULL COMMENT '脱敏响应报文',
 result VARCHAR(24) NOT NULL COMMENT 'STARTED、SUCCESS、FAILED、UNKNOWN',
 error_info VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '错误信息',
 duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '耗时毫秒',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '交互时间',
 PRIMARY KEY(id), KEY idx_log_ticket(company_code,ticket_sn,id), KEY idx_log_time(company_code,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库交互日志，每次请求回传和重试独立保留';
