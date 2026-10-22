CREATE TABLE IF NOT EXISTS oms_message_outbox (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(64) NOT NULL,service_code VARCHAR(24) NOT NULL,
 event_id VARCHAR(40) NOT NULL,message_type VARCHAR(40) NOT NULL,business_id VARCHAR(64) NOT NULL,business_version BIGINT NOT NULL,business_number VARCHAR(128) NOT NULL,
 dedupe_key CHAR(64) NOT NULL,trace_id VARCHAR(40) NOT NULL,source_trace_id VARCHAR(256) NULL,schema_version INT NOT NULL DEFAULT 1,
 publish_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',process_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 send_attempts INT NOT NULL DEFAULT 0,consume_attempts INT NOT NULL DEFAULT 0,business_attempts INT NOT NULL DEFAULT 0,
 next_publish_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),next_process_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 publish_token VARCHAR(40) NULL,publish_until DATETIME(3) NULL,work_token VARCHAR(40) NULL,work_until DATETIME(3) NULL,
 last_published_at DATETIME(3) NULL,last_consumed_at DATETIME(3) NULL,completed_at DATETIME(3) NULL,
 problem_code VARCHAR(32) NOT NULL DEFAULT 'NONE',parent_event_id VARCHAR(40) NOT NULL DEFAULT '',
 execution_mode VARCHAR(24) NOT NULL DEFAULT '',last_publish_error VARCHAR(255) NOT NULL DEFAULT '',last_error VARCHAR(1000) NOT NULL DEFAULT '',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY event_identity(event_id),UNIQUE KEY business_notification(company_code,service_code,dedupe_key),
 KEY publisher_due(company_code,service_code,publish_state,next_publish_at,id),KEY compensation_due(company_code,service_code,process_state,next_process_at,id),
 KEY business_lookup(company_code,service_code,business_id,id),KEY number_lookup(company_code,service_code,business_number,id),KEY company_page(company_code,service_code,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_message_attempt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(64) NOT NULL,event_id VARCHAR(40) NOT NULL,
 stage VARCHAR(24) NOT NULL,result VARCHAR(24) NOT NULL,mode VARCHAR(24) NOT NULL DEFAULT '',message VARCHAR(1000) NOT NULL DEFAULT '',duration_ms BIGINT NOT NULL DEFAULT 0,
 sw_trace_id VARCHAR(256) NULL,actor VARCHAR(100) NOT NULL DEFAULT '',create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),KEY event_attempt(company_code,event_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
