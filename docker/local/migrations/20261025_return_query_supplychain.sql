CREATE TABLE IF NOT EXISTS wms_return_query_dirty (
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司，来源和变更确认均按公司隔离',
 owner_kind VARCHAR(16) NOT NULL COMMENT 'REFUND退款退回计划或EXCHANGE换货',
 source_id BIGINT NOT NULL COMMENT '退款退回计划ID或换货ID',
 work_version BIGINT NOT NULL DEFAULT 1 COMMENT '仓库进度变化版本，确认旧版本不会丢弃新变化',
 PRIMARY KEY(company_code,owner_kind,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库退货查询变更，读取或确认此表不推进仓库业务';
