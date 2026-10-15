CREATE TABLE IF NOT EXISTS oms_order_auto_allocation (
 company_code VARCHAR(50) NOT NULL COMMENT '公司编码',
 order_id BIGINT NOT NULL COMMENT '业务订单ID',
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '自动分仓执行状态，与业务阶段独立',
 paused TINYINT NOT NULL DEFAULT 0 COMMENT '本单自动分仓暂停，不影响人工分仓',
 attempts INT NOT NULL DEFAULT 0 COMMENT '累计执行次数',
 failures INT NOT NULL DEFAULT 0 COMMENT '连续系统故障次数',
 reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前等待或处理原因',
 trigger_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最近业务触发原因',
 next_attempt DATETIME NULL COMMENT '下次允许执行时间；人工处理或完成为空',
 last_attempt DATETIME NULL COMMENT '最近尝试时间',
 lease_token VARCHAR(64) NOT NULL DEFAULT '' COMMENT '执行租约令牌',
 lease_until DATETIME NULL COMMENT '执行租约过期时间',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次进入自动分仓时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,order_id),
 KEY idx_auto_due(company_code,paused,state,next_attempt,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单自动分仓持久化待办';
