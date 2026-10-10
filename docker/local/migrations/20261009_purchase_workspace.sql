CREATE TABLE IF NOT EXISTS purchase_line (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '采购计划明细主键ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司编码，用于租户隔离',
 po_sn VARCHAR(255) NOT NULL COMMENT '采购单号，关联同公司po_info.po_sn',
 sku_sn VARCHAR(255) NOT NULL COMMENT '商品SKU编码，同一公司同一采购单内唯一',
 goods_name VARCHAR(255) COMMENT '采购商品名称快照',
 goods_sn VARCHAR(255) COMMENT '采购商品货号快照',
 barcode_sn VARCHAR(255) COMMENT '采购商品条码快照',
 quantity INT NOT NULL COMMENT '计划采购数量，用于控制分批到货总量',
 purchase_price DECIMAL(15,2) NOT NULL COMMENT '采购单价，保留两位小数；计划金额为数量乘单价',
 UNIQUE KEY uk_purchase_sku(company_code,po_sn,sku_sn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='采购计划商品明细表，保存采购商品、数量和单价，审核后锁定计划';
CREATE TABLE IF NOT EXISTS purchase_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '业务操作事件主键ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司编码，用于租户隔离',
 document_sn VARCHAR(255) NOT NULL COMMENT '业务对象编码：采购单号、到货单号、出入库单号或供应商编码',
 action VARCHAR(64) NOT NULL COMMENT '操作名称，如创建、审核、收货、入账、作废或数据修复',
 operator VARCHAR(64) NOT NULL COMMENT '操作人账号或系统执行标识',
 message VARCHAR(1000) NOT NULL COMMENT '操作说明、处理结果或失败原因',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作事件记录时间',
 KEY idx_purchase_event(company_code,document_sn,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购工作台业务操作日志表，记录供应商、采购、到货和出入库操作轨迹';
