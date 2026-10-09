ALTER TABLE rule_stock_info ADD COLUMN allocation_type TINYINT NOT NULL DEFAULT 1 COMMENT '分货类型：1普通分货，2锁库分货' AFTER rule_type;
-- Legacy rule_type=3 explicitly meant a one-time locking allocation.
UPDATE rule_stock_info SET allocation_type=2,rule_type=2 WHERE rule_type=3;
