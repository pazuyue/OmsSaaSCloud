-- 执行于订单业务库；新增字段和索引，不迁移或回写历史单据。
ALTER TABLE oms_platform_refund
 ADD COLUMN source_kind VARCHAR(24) NOT NULL DEFAULT 'JDP' COMMENT '最近有效来源：JDP推送/ORDINARY_API普通官方接口；不代表专项退款接入';

ALTER TABLE oms_platform_refund_snapshot
 ADD COLUMN source_kind VARCHAR(24) NOT NULL DEFAULT 'JDP' COMMENT '本次来源记录获取方式：JDP或ORDINARY_API';

ALTER TABLE oms_refund_operation
 MODIFY COLUMN action VARCHAR(24) NOT NULL COMMENT '平台动作：AGREE_RETURN同意退货/REVIEW审核/PAY付款/PAY_CODE验证码/REFUSE拒绝/其他物流协商动作',
 MODIFY COLUMN state VARCHAR(24) NOT NULL COMMENT 'PENDING待提交/RUNNING提交中/SUCCESS操作返回成功/FAILED明确失败/UNKNOWN待核对/ACCEPTED受理/CLOSED结束；SUCCESS不等于资金到账',
 MODIFY COLUMN fee DECIMAL(20,2) NOT NULL COMMENT '本次平台退款申请金额，单位元；接口按分换算，不代表实际付款成功金额',
 ADD COLUMN policy_revision BIGINT NULL COMMENT '采用的店铺自动流程规则版本，人工操作为空；发出前再次核对启用状态及版本',
 ADD COLUMN approved_hash CHAR(64) NULL COMMENT '操作采用的已审核退款内容摘要；用于付款审核关联',
 ADD COLUMN request_json JSON NULL COMMENT '冻结的平台业务参数，不保存短信验证码或账号令牌',
 ADD COLUMN response_json JSON NULL COMMENT '平台原操作响应；与最终资金成功事实分开核对',
 ADD COLUMN lease_until DATETIME NULL COMMENT '提交租约到期时间，超时后只查询原结果';

ALTER TABLE oms_refund
 ADD COLUMN platform_context JSON NULL COMMENT '最近平台能力与可用操作缓存；列表和详情不逐条请求平台',
 ADD COLUMN platform_checked_at DATETIME NULL COMMENT '最近一次主动查询平台操作能力的时间';
ALTER TABLE oms_order_shipment_line ADD INDEX refund_original_line(company_code,order_line_id,id) COMMENT '退款按原商品行批量查询各仓实发数量';
ALTER TABLE oms_refund_goods_task ADD INDEX return_source(company_code,shipment_line_id,state,id) COMMENT '原发货来源退回额度校验，防止并发售后超占';
ALTER TABLE oms_refund_operation ADD INDEX execution_scope(company_code,refund_id,action,state,id) COMMENT '检查原平台操作和付款幂等状态';
ALTER TABLE oms_refund_api_item ADD INDEX shop_work(company_code,channel_id,state,id) COMMENT '店铺普通退款详情待处理及失败查询';
