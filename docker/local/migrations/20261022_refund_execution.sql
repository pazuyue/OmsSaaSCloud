-- 普通退款按原履约来源处理。只新增结构，不回写历史业务单据。
ALTER TABLE oms_refund
 ADD COLUMN initial_fulfillment_stage VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' COMMENT '首次转单实际履约阶段：UNSHIPPED未出库/PART_SHIPPED部分出库/SHIPPED全部出库/CANCELED全取消/UNKNOWN待核对',
 ADD COLUMN initial_fulfillment_snapshot JSON NULL COMMENT '首次转单原商品与各仓履约快照，只写一次，不用当前状态覆盖';
CREATE TABLE IF NOT EXISTS oms_refund_goods_task (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '货物处理任务主键',
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码，所有查询和幂等键必须包含',
 refund_id BIGINT NOT NULL COMMENT '业务退款单主键，一张退款可拆多个货物任务',
 order_id BIGINT NOT NULL COMMENT '原业务销售订单主键',
 order_line_id BIGINT NOT NULL COMMENT '原销售商品履约行；套装福袋按原组件追溯',
 shipment_line_id BIGINT NULL COMMENT '原发货明细主键；取消未分仓需求时为空',
 plan_input JSON NOT NULL COMMENT '已审核的原商品来源、动作、退回仓和联系地址输入',
 action VARCHAR(24) NOT NULL COMMENT 'CANCEL取消未发/INTERCEPT在途拦截/RETURN退回/REFUSAL拒收',
 quantity BIGINT NOT NULL COMMENT '本任务明确处理的实际数量，不从退款金额推导',
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待执行/PROCESSING处理中/WAIT_WAREHOUSE待仓库/WAIT_RETURN待退回/UNKNOWN结果未明/MANUAL待人工/DONE完成/CANCELED停止',
 approved_hash CHAR(64) NOT NULL COMMENT '任务依据的已审核退款内容摘要；变化后停止未执行动作',
 request_id VARCHAR(64) NOT NULL COMMENT '稳定幂等请求编号，结果不明时沿用原请求核对',
 command_json JSON NULL COMMENT '冻结的库存或仓库指令，只在发出前生成一次',
 cutoff_id BIGINT NULL COMMENT '关联原订单截单申请，禁止重复创建仓库取消',
 return_plan_id BIGINT NULL COMMENT '关联原来源退回计划，沿用现有合包及库存入账',
 result_message VARCHAR(500) NOT NULL DEFAULT '' COMMENT '最近处理结果、阻塞原因或人工下一步',
 lease_token VARCHAR(32) NULL COMMENT '后台执行租约令牌，防止并发重复消费',
 lease_until DATETIME NULL COMMENT '执行租约到期时间，超时接管后先查原结果',
 next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可执行时间，通过索引有界扫描，不使用sleep',
 control_version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本，防止覆盖并发操作',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '任务最后更新时间',
 PRIMARY KEY(id),
 UNIQUE KEY request_scope(company_code,request_id) COMMENT '同公司稳定业务请求去重',
 KEY refund_scope(company_code,refund_id,id) COMMENT '退款详情一次批量读取任务',
 KEY source_claim(company_code,order_line_id,state,id) COMMENT '同原商品行并发取消额度校验',
 KEY due(company_code,state,next_attempt,id) COMMENT '后台仅扫描到期未结束任务'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款按原商品来源拆分的货物任务；支持同单未发取消与已发退回并行';

ALTER TABLE oms_order_line ADD COLUMN refund_block_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '本商品行的退款履约阻塞原因；分仓与下发共同校验，不影响其他子单';
