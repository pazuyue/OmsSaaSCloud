CREATE TABLE IF NOT EXISTS oms_order_review_config (
 company_code VARCHAR(50) NOT NULL COMMENT '公司编码',
 channel_id BIGINT NOT NULL COMMENT '店铺渠道ID',
 policy_json JSON NOT NULL COMMENT '独立审单规则与库存审核条件',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '规则并发版本',
 updated_by VARCHAR(64) NOT NULL DEFAULT '',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺审单规则';
CREATE TABLE IF NOT EXISTS oms_order_review_event (
 id BIGINT NOT NULL AUTO_INCREMENT,
 company_code VARCHAR(50) NOT NULL,
 order_id BIGINT NOT NULL COMMENT '业务订单ID',
 action VARCHAR(32) NOT NULL COMMENT 'AUTO_REVIEW/RECHECK/HUMAN_APPROVE',
 decision VARCHAR(24) NOT NULL COMMENT '本次审核判定',
 assessment JSON NOT NULL COMMENT '规则版本、无个人信息的事实及逐项结果',
 operator_name VARCHAR(64) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id),
 KEY idx_review_event(company_code,order_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核依据与人工处理留痕';
