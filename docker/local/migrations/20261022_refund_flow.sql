-- 普通退款自动流程：店铺规则、按退款的独立任务、普通官方接口增量游标。
CREATE TABLE IF NOT EXISTS oms_refund_flow_policy (
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码',
 channel_id BIGINT NOT NULL COMMENT '适用店铺渠道编号',
 enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用已审核退款的自动流程，默认关闭',
 policy_json JSON NOT NULL COMMENT '自动同意退货、全额未发取消、退款审核、付款主体、金额上限、平台地址等明确规则',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '店铺自动规则版本，任务记录所采用版本',
 updated_by VARCHAR(64) NOT NULL COMMENT '最近保存规则的操作人',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近保存时间',
 PRIMARY KEY(company_code,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺退款自动流程设置，接口能力开关与自动业务规则分开维护';
CREATE TABLE IF NOT EXISTS oms_refund_flow_task (
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码',
 refund_id BIGINT NOT NULL COMMENT '业务退款主键，每张退款独立调度',
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待检查/WAITING等待业务事实/MANUAL需人工/DONE已完成',
 work_version BIGINT NOT NULL DEFAULT 1 COMMENT '流程通知版本，消息去重和源变化后唤醒使用',
 policy_revision BIGINT NULL COMMENT '最近执行采用的店铺规则版本',
 last_message VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前阻塞原因、下一步或最近自动处理结果',
 next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次检查时间，消息调度与数据库有界补扫使用',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次进入自动流程时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后进度更新时间',
 PRIMARY KEY(company_code,refund_id),
 KEY due(company_code,state,next_attempt,refund_id) COMMENT '只扫描到期自动任务，避免扫描全部退款历史'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按退款单独调度的售后自动流程，不在公司扫描线程等待平台网络';
CREATE TABLE IF NOT EXISTS oms_refund_api_cursor (
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码',
 channel_id BIGINT NOT NULL COMMENT '普通退款官方接口补充同步的店铺',
 enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用普通退款官方接口增量同步，默认关闭',
 cursor_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '已完整提交窗口的更新时间游标，独立于JDP游标',
 window_start DATETIME NULL COMMENT '当前固定分页窗口开始时间，含2分钟重叠去重',
 window_end DATETIME NULL COMMENT '当前固定分页窗口结束时间，全部分页成功后才推进游标',
 page_no INT NOT NULL DEFAULT 1 COMMENT '当前窗口待读取页码，每次仅拉一页',
 work_version BIGINT NOT NULL DEFAULT 1 COMMENT '同步消息版本，防止旧通知覆盖新配置或分页',
 lease_token VARCHAR(32) NULL COMMENT '同店铺同步租约令牌，不与其他退款平台操作共用锁',
 lease_until DATETIME NULL COMMENT '同步执行租约到期时间',
 next_run DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次可检查时间，索引扫描有界调度',
 last_success DATETIME NULL COMMENT '最近成功保存一页普通退款的时间',
 last_error VARCHAR(500) NOT NULL DEFAULT '' COMMENT '最近失败原因，失败不推进窗口或游标',
 PRIMARY KEY(company_code,channel_id),
 KEY due(company_code,enabled,next_run,channel_id) COMMENT '按公司调度到期店铺，避免全表扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='普通退款官方接口增量同步，补足JDP对交易完成后售后的不保障范围';

CREATE TABLE IF NOT EXISTS oms_refund_api_item (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '普通退款详情待拉取记录主键',
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码',
 channel_id BIGINT NOT NULL COMMENT '平台授权店铺渠道编号',
 refund_id VARCHAR(64) NOT NULL COMMENT '平台退款编号，字符串避免大整数精度丢失',
 source_modified DATETIME NOT NULL COMMENT '列表返回的退款更新时间，用于持久化增量去重',
 list_hash CHAR(64) NOT NULL COMMENT '列表快照摘要，避免重复生成相同详情任务',
 list_payload JSON NOT NULL COMMENT '原始列表快照，只作接入依据，不当作完整退款覆盖原单',
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待拉详情/DONE完整详情已保存/FAILED待重试核对',
 platform_refund_id BIGINT NULL COMMENT '完整详情保存后的平台退款主键',
 last_error VARCHAR(500) NOT NULL DEFAULT '' COMMENT '完整详情未保存原因，列表游标推进不丢失该持久任务',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收到列表快照时间',
 PRIMARY KEY(id),
 UNIQUE KEY observed(company_code,channel_id,refund_id,source_modified,list_hash) COMMENT '同店铺列表记录及内容去重',
 KEY work_scope(company_code,state,id) COMMENT '分页查询未完成详情',
 KEY original_scope(company_code,channel_id,refund_id,id) COMMENT '按平台退款号追溯来源快照和任务'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='普通退款列表接入与完整详情任务，列表不逐条同步等待远程详情';
