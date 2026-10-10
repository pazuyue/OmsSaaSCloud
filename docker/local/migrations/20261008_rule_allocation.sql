ALTER TABLE rule_stock_info ADD COLUMN allocation_type TINYINT NOT NULL DEFAULT 1 COMMENT '分货类型：1普通分货，2锁库分货' AFTER rule_type;
