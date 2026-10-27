CREATE TABLE IF NOT EXISTS purchase_return_continuation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '继续退仓申请ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 return_id BIGINT NOT NULL COMMENT '原退仓单ID，一张原单仅允许一轮剩余转后续',
 request_key VARCHAR(64) NOT NULL COMMENT '继续申请幂等身份',
 request_hash CHAR(64) NOT NULL COMMENT '申请内容摘要，重试内容不得变化',
 reason VARCHAR(200) NOT NULL COMMENT '结束原单剩余并继续退仓的业务依据',
 actor VARCHAR(64) NOT NULL COMMENT '明确发起本次继续申请的操作人',
 state VARCHAR(16) NOT NULL DEFAULT 'WAIT_RELEASE' COMMENT 'WAIT_RELEASE等待原占用释放、DRAFT_CREATED后续草稿已生成',
 child_id BIGINT NULL COMMENT '关联后续退仓草稿ID，须独立审核',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
 UNIQUE KEY original_once(company_code,return_id) COMMENT '重复点击只生成一个后续申请',
 UNIQUE KEY request_once(company_code,request_key) COMMENT '请求重放身份唯一'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退仓剩余继续申请，先释放再生成关联草稿';
CREATE TABLE IF NOT EXISTS purchase_return_fund (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '款项凭证ID',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',return_id BIGINT NOT NULL COMMENT '原采购退仓ID',
 request_key VARCHAR(64) NOT NULL COMMENT '登记或冲销请求幂等键',request_hash CHAR(64) NOT NULL COMMENT '不可变请求内容摘要',
 kind VARCHAR(16) NOT NULL COMMENT 'REFUND退款、CREDIT抵扣、REVERSAL冲销原凭证',
 amount DECIMAL(18,2) NOT NULL COMMENT '正数原凭证金额或负数冲销金额',
 original_id BIGINT NULL COMMENT '冲销关联原凭证ID，一张凭证只允许完整冲销一次',
 evidence VARCHAR(1000) NOT NULL COMMENT '实际退款抵扣凭证或冲销依据，不代表系统执行付款',
 actor VARCHAR(64) NOT NULL COMMENT '登记人',create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
 UNIQUE KEY request_once(company_code,request_key) COMMENT '重复登记不重复计款',
 UNIQUE KEY reversal_once(company_code,original_id) COMMENT '防止重复冲销同一凭证',
 KEY parent_funds(company_code,return_id,id) COMMENT '按退仓单读取凭证及汇总'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退仓多笔退款抵扣与不可变冲销明细';
CREATE TABLE IF NOT EXISTS purchase_return_supplier_receipt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供应商收货事实ID',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',return_id BIGINT NOT NULL COMMENT '原采购退仓ID',
 request_key VARCHAR(64) NOT NULL COMMENT '收货登记幂等键',request_hash CHAR(64) NOT NULL COMMENT '不可变收货内容摘要',
 decision VARCHAR(16) NOT NULL COMMENT 'ACCEPT签收、REFUSE拒收、SHORT短收待核查；短收不消耗可登记余额',
 payload_json JSON NOT NULL COMMENT '逐行原退仓明细与本次签收拒收或短收数量',
 evidence VARCHAR(1000) NOT NULL COMMENT '供应商收货质检或物流核对凭证',actor VARCHAR(64) NOT NULL COMMENT '登记人',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
 UNIQUE KEY request_once(company_code,request_key) COMMENT '重复回放不重复登记数量',KEY parent_receipts(company_code,return_id,id) COMMENT '原单供应商收货记录'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商收货结果与原始凭证，不直接改变库存';
CREATE TABLE IF NOT EXISTS purchase_return_reclaim (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供应商拒收收回入库ID',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 reclaim_sn VARCHAR(64) NOT NULL COMMENT '独立收回入库单号CGH前缀',return_id BIGINT NOT NULL COMMENT '原退仓单ID',
 request_key VARCHAR(64) NOT NULL COMMENT '创建幂等键',request_hash CHAR(64) NOT NULL COMMENT '创建内容摘要',
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT待审核、ACTIVE执行中、COMPLETED实收入账完成、CANCELED未收货取消完成',
 store_code VARCHAR(64) NOT NULL COMMENT '原采购出库虚仓，收回仍入原仓',inbound_mode TINYINT NOT NULL COMMENT '1真实奇门入库回传、2自动虚拟入库',
 quantity BIGINT NOT NULL COMMENT '计划收回数量，须来自原实际出库且供应商已拒收数量',accepted_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '仓库实收累计',posted_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '已恢复库存累计',
 receipt_final TINYINT NOT NULL DEFAULT 0 COMMENT '仓库已最终确认收货，短收不得虚增库存',
 dispatch_state VARCHAR(24) NOT NULL DEFAULT 'UNSENT' COMMENT 'UNSENT未下发、PENDING待下发、SENDING结果未定、UNKNOWN结果未知、ACCEPTED仓库受理、FAILED明确拒单、VIRTUAL虚拟执行、CANCELED停止成功',
 external_order VARCHAR(100) NOT NULL DEFAULT '' COMMENT '仓库原入库单号',payload_json JSON NULL COMMENT '审核固定的原仓库货主商品批次和地址快照',
 reason VARCHAR(1000) NOT NULL COMMENT '拒收收回依据',carrier_code VARCHAR(64) NOT NULL COMMENT '供应商退回物流名称或编码',waybill VARCHAR(100) NOT NULL COMMENT '供应商退回本仓运单号',
 control_version BIGINT NOT NULL DEFAULT 1 COMMENT '审核重试或取消版本',last_error VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '最近执行异常或回传待核对原因',
 receipt_review_pending TINYINT NOT NULL DEFAULT 0 COMMENT '原身份验证后的回传内容冲突，须仓库更正',
 created_by VARCHAR(64) NOT NULL COMMENT '创建人',approved_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '审核人',
 attempts INT NOT NULL DEFAULT 0 COMMENT '处理次数，用于失败退避',next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下一次补偿时间',
 lease_token VARCHAR(64) NULL COMMENT '执行租约',lease_until DATETIME NULL COMMENT '租约到期时间',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变更时间',
 UNIQUE KEY company_sn(company_code,reclaim_sn) COMMENT '公司收回入库号唯一',UNIQUE KEY request_once(company_code,request_key) COMMENT '重复创建不重复建入库单',
 KEY original_claim(company_code,return_id,state,id) COMMENT '原退仓拒收可收回额度',KEY due_work(company_code,next_attempt_at,state,id) COMMENT '到期补偿扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退仓被供应商拒收后的独立收回入库单，奇门QTRK';
CREATE TABLE IF NOT EXISTS purchase_return_reclaim_line (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '收回入库行ID，仓库原行号',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',reclaim_id BIGINT NOT NULL COMMENT '收回入库单ID',return_line_id BIGINT NOT NULL COMMENT '原实际退仓出库行ID',
 quantity BIGINT NOT NULL COMMENT '计划收回数量',accepted_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '仓库已确认实收',posted_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '库存已入账',
 UNIQUE KEY original_line(company_code,reclaim_id,return_line_id) COMMENT '同单来源不重复',KEY parent_lines(company_code,reclaim_id,id) COMMENT '原入库明细',KEY source_claim(company_code,return_line_id,reclaim_id) COMMENT '原拒收数量并发校验'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拒收收回计划来源，不改写原出库行';
CREATE TABLE IF NOT EXISTS purchase_return_reclaim_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '收回实收回传事件ID',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',reclaim_id BIGINT NOT NULL COMMENT '独立收回入库单ID',message_id VARCHAR(128) NOT NULL COMMENT '仓库增量消息号',payload_hash CHAR(64) NOT NULL COMMENT '消息内容摘要',payload_json JSON NOT NULL COMMENT '已验签原始回传解析结果',
 source_log_id BIGINT NOT NULL DEFAULT 0 COMMENT '仓库原回传日志ID',quantity BIGINT NOT NULL COMMENT '本次确认实收增量',state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待入账、POSTED已入账、FAILED入账失败',last_error VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '本次入账错误',create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
 UNIQUE KEY receipt_once(company_code,reclaim_id,message_id) COMMENT '重复回传不重复收货或入账',KEY pending_post(company_code,reclaim_id,state,id) COMMENT '仅补偿未成功事件'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商拒收收回的仓库实收事件';
CREATE TABLE IF NOT EXISTS purchase_return_reclaim_receipt_line (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '收回入库实收来源ID，S来源前缀',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',reclaim_id BIGINT NOT NULL COMMENT '独立收回入库ID',event_id BIGINT NOT NULL COMMENT '原实收事件ID',reclaim_line_id BIGINT NOT NULL COMMENT '仓库原计划行',return_line_id BIGINT NOT NULL COMMENT '原退仓商品行',
 inventory_type VARCHAR(2) NOT NULL COMMENT '实际实收质量ZP正品或CP次品，允许仓库质检后变化',quantity BIGINT NOT NULL COMMENT '本次实际实收数量',origin_relation VARCHAR(100) NOT NULL COMMENT '对应库存RECEIVE流水关系号，PURCHASE_RECLAIM_实收来源ID',posted TINYINT NOT NULL DEFAULT 0 COMMENT '该真实来源是否已成功入账',
 UNIQUE KEY event_line_grade(company_code,event_id,reclaim_line_id,inventory_type) COMMENT '同一次回传原行正次品唯一',KEY receipt_sources(company_code,reclaim_id,posted,id) COMMENT '再次退仓来源查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收回后可再次退仓的新增真实入库来源，保留原采购成本';
