CREATE TABLE IF NOT EXISTS oms_stock_transfer (
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',document_sn VARCHAR(64) NOT NULL COMMENT '跨实仓调拨业务单号',
 request_hash CHAR(64) NOT NULL COMMENT '原调拨占用请求摘要',state VARCHAR(16) NOT NULL DEFAULT 'HELD' COMMENT 'HELD可继续出库、STOPPED仅释放未出库余额',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '库存占用时间',PRIMARY KEY(company_code,document_sn) COMMENT '调拨来源唯一'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨库存事务边界；在途独立于公司实物库存';
CREATE TABLE IF NOT EXISTS oms_stock_transfer_line (
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',document_sn VARCHAR(64) NOT NULL COMMENT '调拨单号',line_key BIGINT NOT NULL COMMENT '原业务明细ID',
 source_batch_id BIGINT NOT NULL COMMENT '出库原库存批次ID',sku_sn VARCHAR(128) NOT NULL COMMENT '库存SKU',source_store VARCHAR(64) NOT NULL COMMENT '出库虚仓',target_store VARCHAR(64) NOT NULL COMMENT '收货虚仓',
 batch_code VARCHAR(128) NOT NULL COMMENT '原批次编码，全程保留',inventory_type VARCHAR(2) NOT NULL COMMENT 'ZP正品、CP次品，全程保留',unit_cost DECIMAL(18,2) NOT NULL COMMENT '原批次单位成本，禁止调拨重估',stock_unit VARCHAR(16) NOT NULL COMMENT '原库存单位',
 quantity BIGINT NOT NULL COMMENT '审核占用数量',out_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '原仓已实际出库扣账',in_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '目的仓实际入库',recovered_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '独立收回单实际退回原仓',released_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '未出库已释放数量',
 PRIMARY KEY(company_code,document_sn,line_key) COMMENT '原行精确结算',KEY company_transit(company_code,sku_sn,document_sn) COMMENT '公司在途与拥有数量汇总',KEY source_hold(company_code,source_batch_id,document_sn) COMMENT '原批次调拨占用查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨原批次占用和在途余额；在途=出库-目的实收-原仓收回';
CREATE TABLE IF NOT EXISTS oms_stock_movement_event (
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',document_sn VARCHAR(64) NOT NULL COMMENT '调拨或调整单号',message_id VARCHAR(128) NOT NULL COMMENT '动作及原消息幂等键',payload_hash CHAR(64) NOT NULL COMMENT '不可变事件内容摘要',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '库存入账时间',PRIMARY KEY(company_code,document_sn,message_id) COMMENT '相同回传只入账一次'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨和盘点调整库存入账幂等记录';
