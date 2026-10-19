CREATE TABLE IF NOT EXISTS sys_mq_dead_letter (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,fingerprint CHAR(64) NOT NULL,
 business_type VARCHAR(40) NOT NULL,service_code VARCHAR(24) NOT NULL,
 company_code VARCHAR(64) NOT NULL DEFAULT '',event_id VARCHAR(40) NOT NULL DEFAULT '',business_number VARCHAR(128) NOT NULL DEFAULT '',
 verified TINYINT NOT NULL DEFAULT 0,death_reason VARCHAR(40) NOT NULL,business_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',latest_event_id VARCHAR(40) NOT NULL DEFAULT '',
 review_reason VARCHAR(255) NOT NULL DEFAULT '',reviewed_by VARCHAR(100) NOT NULL DEFAULT '',reviewed_at DATETIME(3) NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),checked_at DATETIME(3) NOT NULL,
 UNIQUE KEY dead_identity(fingerprint),KEY queue_page(business_type,id),KEY pending_queue(business_type,verified,business_state,reviewed_at),KEY check_due(service_code,verified,business_state,checked_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_mq_dead_review (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,dead_id BIGINT NOT NULL,actor VARCHAR(100) NOT NULL,reason VARCHAR(255) NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),KEY review_history(dead_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
