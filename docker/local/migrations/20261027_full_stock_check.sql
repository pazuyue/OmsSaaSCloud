-- 全仓检查仅增加检查任务、进度和结果证据；不修改库存或已有业务单据。
CREATE TABLE IF NOT EXISTS warehouse_reconciliation_result (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '检查结果行身份',
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '受信公司身份',
 parent_id BIGINT NOT NULL COMMENT '所属全仓检查任务',
 child_id BIGINT NOT NULL COMMENT '单SKU完整批次检查快照',
 row_hash CHAR(64) NOT NULL COMMENT '同商品批次质量结果摘要，重复执行不重复落行',
 sku_sn VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '原始库存SKU，保留大小写前导零',
 status VARCHAR(40) NOT NULL COMMENT '实物一致、数量差额、字段缺失、查询失败等结果',
 difference BIGINT NULL COMMENT 'WMS实物减OMS实物，未知保持NULL',
 row_json MEDIUMTEXT NOT NULL COMMENT '单行比较数据与虚仓来源，不保存整仓大数组',
 UNIQUE KEY original_row(company_code,parent_id,child_id,row_hash) COMMENT '结果投影幂等',
 KEY result_page(company_code,parent_id,status,id) COMMENT '按任务和结论分页',
 KEY sku_page(company_code,parent_id,sku_sn,id) COMMENT '按任务和SKU定位异常'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全仓库存检查分页结果，仅留证不改账';
CREATE TABLE IF NOT EXISTS warehouse_reconciliation_gate (
 company_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '受信公司身份',
 scope_key CHAR(64) NOT NULL COMMENT '同WMS连接货主实仓查询范围摘要',
 task_id BIGINT NOT NULL DEFAULT 0 COMMENT '当前占用查询通道的任务',
 lease_token VARCHAR(64) NULL COMMENT '本次工作租约随机标识',
 lease_until DATETIME NULL COMMENT '进程异常后自动恢复通道的到期时间',
 PRIMARY KEY(company_code,scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同仓库存查询限流通道，不占用业务库存锁';
