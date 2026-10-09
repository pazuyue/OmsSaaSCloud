-- Historical rules remain unscheduled until explicitly approved through the new daily workflow.
ALTER TABLE rule_stock_info ADD COLUMN interval_minutes INT NOT NULL DEFAULT 5 COMMENT '日常分货执行间隔分钟数，上一轮结束后计时，范围1至1440',
  ADD COLUMN daily_priority INT NOT NULL DEFAULT 100 COMMENT '日常规则优先级，数字越小越优先，同值按规则ID升序',
  ADD COLUMN daily_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '日常调度开关：0未启用或停用，1审核启用；历史规则默认0',
  ADD COLUMN next_run_at DATETIME NULL COMMENT '下次允许创建执行轮次的时间；运行中或停用时为空',
  ADD COLUMN active_run_id BIGINT NULL COMMENT '当前未完成日常执行轮次ID，关联rule_stock_daily_run.id',
  ADD INDEX idx_daily_due(company_code,rule_type,daily_enabled,id),
  ADD INDEX idx_daily_priority(company_code,rule_type,daily_enabled,daily_priority,id);
ALTER TABLE rule_stock_channel_info ADD INDEX idx_channel_rule(channel_id,rule_id);
CREATE TABLE IF NOT EXISTS rule_stock_daily_run (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '日常分货执行轮次ID',
  company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '所属公司编码，租户隔离',
  rule_id BIGINT NOT NULL COMMENT '分货规则ID，关联rule_stock_info.id',
  status VARCHAR(16) NOT NULL DEFAULT 'PREPARING' COMMENT '轮次状态：PREPARING准备商品，RUNNING执行中，SUCCESS成功，PARTIAL部分成功，FAILED失败，STOPPED停用，EXPIRED到期',
  cursor_sku VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '商品清单分批准备游标，已读取的最后一个SKU',
  total INT NOT NULL DEFAULT 0 COMMENT '本轮已纳入清单的SKU数，准备期间可能继续增长',
  success INT NOT NULL DEFAULT 0 COMMENT '本轮成功提交SKU数，包含因优先级全部跳过的商品',
  failed INT NOT NULL DEFAULT 0 COMMENT '本轮失败且已回滚的SKU数',
  config_json MEDIUMTEXT NOT NULL COMMENT '创建轮次时规则、仓库和渠道配置快照JSON',
  operator_name VARCHAR(255) NOT NULL COMMENT '轮次触发人，自动触发记为定时分货',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '轮次创建时间',
  finish_time DATETIME NULL COMMENT '轮次结束、停用或到期时间',
  PRIMARY KEY(id), KEY idx_daily_run(rule_id,id), KEY idx_daily_company(company_code,rule_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='日常分货执行轮次，独立保留每轮结果';
CREATE TABLE IF NOT EXISTS rule_stock_daily_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '日常分货商品执行记录ID',
  run_id BIGINT NOT NULL COMMENT '所属执行轮次ID，关联rule_stock_daily_run.id',
  sku_sn VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '商品SKU编码，同一轮次唯一',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '商品状态：PENDING未处理，SUCCESS已提交，FAILED已回滚；轮次停止后PENDING不再执行',
  allocated_quantity INT NOT NULL DEFAULT 0 COMMENT '实际负责渠道的目标配额合计，排除因优先级跳过的渠道；非累加库存量',
  detail_json MEDIUMTEXT NULL COMMENT '本轮可分库存、各渠道目标及前后可售量、跳过原因JSON',
  error_message VARCHAR(500) NOT NULL DEFAULT '' COMMENT '本商品失败原因；事务失败不影响其他商品',
  attempts INT NOT NULL DEFAULT 0 COMMENT '本轮商品已记录的执行尝试次数',
  modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '商品执行结果最近更新时间',
  PRIMARY KEY(id), UNIQUE KEY uk_daily_sku(run_id,sku_sn), KEY idx_daily_pending(run_id,status,sku_sn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='日常分货每轮商品结果，与普通一次性执行记录分开';
