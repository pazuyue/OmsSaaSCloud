CREATE TABLE IF NOT EXISTS oms_stock_care (
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',document_sn VARCHAR(64) NOT NULL COMMENT '采购退货或质量冻结来源单号',
 purpose VARCHAR(16) NOT NULL COMMENT 'RETURN退供占用、QUALITY质量冻结',
 payload_hash CHAR(64) NOT NULL COMMENT '原锁定请求摘要，重复请求内容不可改变',
 state VARCHAR(16) NOT NULL DEFAULT 'HELD' COMMENT 'HELD有剩余锁定、RELEASED已释放、COMPLETED全部消耗',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锁定创建时间',
 PRIMARY KEY(company_code,document_sn) COMMENT '来源单据幂等键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退供及质量库存锁定来源，禁止释放其他业务库存';
CREATE TABLE IF NOT EXISTS oms_stock_care_line (
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',document_sn VARCHAR(64) NOT NULL COMMENT '锁定来源单号',
 line_key BIGINT NOT NULL COMMENT '原业务明细ID',batch_id BIGINT NOT NULL COMMENT '实际库存批次ID',
 sku_sn VARCHAR(128) NOT NULL COMMENT '库存SKU',store_code VARCHAR(64) NOT NULL COMMENT '原虚仓',
 inventory_type VARCHAR(2) NOT NULL COMMENT 'ZP正品、CP次品',quantity BIGINT NOT NULL COMMENT '原锁定数量',
 consumed_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '已实际出库扣账数量',released_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '已取消释放数量',
 PRIMARY KEY(company_code,document_sn,line_key) COMMENT '按原行精确结算',
 KEY sku_hold(company_code,sku_sn,document_sn) COMMENT '按当前页SKU批量汇总质量冻结',
 KEY batch_held(company_code,batch_id,document_sn) COMMENT '库存批次冻结与退供来源查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退供和质量冻结的原批次正次品占用余额';
CREATE TABLE IF NOT EXISTS oms_stock_care_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '库存处理事件ID',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 document_sn VARCHAR(64) NOT NULL COMMENT '原来源单号',message_id VARCHAR(128) NOT NULL COMMENT '本次业务事件身份',
 payload_hash CHAR(64) NOT NULL COMMENT '事件内容摘要',create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入账时间',
 UNIQUE KEY event_identity(company_code,document_sn,message_id) COMMENT '出库回传及释放幂等键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退货库存扣账及冻结释放幂等事件';
CREATE TABLE IF NOT EXISTS oms_quality_order (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '质量处理单ID',company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 quality_sn VARCHAR(64) NOT NULL COMMENT '质量处理单号ZJ前缀',request_key VARCHAR(64) NOT NULL COMMENT '创建请求幂等键',
 request_hash CHAR(64) NOT NULL COMMENT '创建内容摘要',action VARCHAR(16) NOT NULL COMMENT 'FREEZE冻结、UNFREEZE按原冻结释放、CP_TO_ZP次品转正',
 batch_id BIGINT NOT NULL COMMENT '实际库存批次ID',sku_sn VARCHAR(128) NOT NULL COMMENT '库存SKU',store_code VARCHAR(64) NOT NULL COMMENT '原虚仓',
 inventory_type VARCHAR(2) NOT NULL COMMENT 'ZP正品或CP次品',quantity BIGINT NOT NULL COMMENT '处理数量',
 source_sn VARCHAR(64) NOT NULL DEFAULT '' COMMENT '解冻须绑定原冻结单号，不可释放别的锁定',
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT待审核、COMPLETED已审核执行、REJECTED已驳回',
 reason VARCHAR(1000) NOT NULL COMMENT '质检及冻结原因',evidence VARCHAR(1000) NOT NULL COMMENT '质检凭证或冻结依据；转正须确认仓库实际质检结果',
 warehouse_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '转正前已核对仓库质检结果，不代表OMS调用了仓库质量变更接口',
 control_version BIGINT NOT NULL DEFAULT 1 COMMENT '审核版本防并发覆盖',created_by VARCHAR(64) NOT NULL COMMENT '创建人',
 reviewed_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '审核人',review_reason VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '审核或驳回依据',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近处理时间',
 UNIQUE KEY company_sn(company_code,quality_sn) COMMENT '质量单号唯一',UNIQUE KEY company_request(company_code,request_key) COMMENT '重复提交不重复建单',
 KEY company_page(company_code,state,id) COMMENT '公司状态分页',KEY batch_source(company_code,batch_id,id) COMMENT '按批次追溯质量处理'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存质量冻结解冻转正申请与审核证据，不修改原实收入库事实';
