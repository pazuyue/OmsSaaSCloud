-- Lifetime quota per reusable strategy rule, shared by all associated shops.
-- No historical document backfill. Counting starts when a rule first enables its quota.
CREATE TABLE IF NOT EXISTS oms_gift_quota (
 company_code VARCHAR(50) NOT NULL, group_id BIGINT NOT NULL,
 rule_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 max_count BIGINT NOT NULL, used_count BIGINT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,group_id,rule_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_gift_quota_award (
 company_code VARCHAR(50) NOT NULL, platform_order_id BIGINT NOT NULL,
 plan_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
 rule_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 requested_count BIGINT NOT NULL, granted_count BIGINT NOT NULL, max_count BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,platform_order_id,group_id,rule_key),
 KEY plan_awards(company_code,plan_id), KEY rule_awards(company_code,group_id,rule_key,platform_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
