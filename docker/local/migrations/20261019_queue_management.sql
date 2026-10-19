CREATE TABLE IF NOT EXISTS sys_mq_queue_policy (
 business_type VARCHAR(32) PRIMARY KEY,desired_mode VARCHAR(16) NOT NULL DEFAULT 'RUNNING',concurrency INT NOT NULL,prefetch INT NOT NULL,
 retry_limit INT NOT NULL DEFAULT 8,retry_base_sec INT NOT NULL DEFAULT 15,retry_max_sec INT NOT NULL DEFAULT 300,
 compensate_after_sec INT NOT NULL DEFAULT 60,compensate_batch INT NOT NULL DEFAULT 2,
 app_rate INT NOT NULL DEFAULT 2,shop_rate INT NOT NULL DEFAULT 1,
 alarm_backlog INT NOT NULL DEFAULT 100,alarm_due_sec INT NOT NULL DEFAULT 120,alarm_failure_pct INT NOT NULL DEFAULT 20,
 revision BIGINT NOT NULL DEFAULT 1,updated_by VARCHAR(64) NOT NULL DEFAULT 'system',update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO sys_mq_queue_policy(business_type,concurrency,prefetch) VALUES ('PRODUCT_PULL',2,1),('PRODUCT_BIND',1,1),('SALES_DOCUMENT',2,5);
INSERT IGNORE INTO sys_mq_queue_policy(business_type,concurrency,prefetch) VALUES ('ORDER_PULL',2,1),('ORDER_CONVERT',2,1);
INSERT IGNORE INTO sys_mq_queue_policy(business_type,concurrency,prefetch) VALUES ('REFUND_PULL',1,1),('REFUND_CONVERT',1,1);
INSERT IGNORE INTO sys_mq_queue_policy(business_type,concurrency,prefetch) VALUES ('EXCHANGE_PULL',1,1),('EXCHANGE_CONVERT',1,1);
CREATE TABLE IF NOT EXISTS sys_mq_instance (
 business_type VARCHAR(32) NOT NULL,instance_id VARCHAR(96) NOT NULL,service_code VARCHAR(16) NOT NULL,applied_version BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(24) NOT NULL DEFAULT 'STARTING',actual_consumers INT NOT NULL DEFAULT 0,active_work INT NOT NULL DEFAULT 0,
 last_error VARCHAR(255) NOT NULL DEFAULT '',stats_json MEDIUMTEXT NULL,last_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(business_type,instance_id),KEY service_instances(service_code,last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_mq_operation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,business_type VARCHAR(32) NOT NULL,revision BIGINT NOT NULL,actor VARCHAR(64) NOT NULL,reason VARCHAR(255) NOT NULL,
 before_json TEXT NOT NULL,after_json TEXT NOT NULL,create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),KEY queue_operations(business_type,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_mq_rate_slot (
 slot_key CHAR(64) PRIMARY KEY,next_at DATETIME(3) NOT NULL,KEY expired_slots(next_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_mq_queue_snapshot (
 business_type VARCHAR(32) PRIMARY KEY,payload_json TEXT NOT NULL,sampled_at DATETIME(3) NOT NULL,last_error VARCHAR(255) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_mq_alert (
 business_type VARCHAR(32) NOT NULL,rule_code VARCHAR(32) NOT NULL,state VARCHAR(16) NOT NULL,
 message VARCHAR(255) NOT NULL,first_seen DATETIME(3) NOT NULL,last_seen DATETIME(3) NOT NULL,
 PRIMARY KEY(business_type,rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_mq_alert_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,business_type VARCHAR(32) NOT NULL,rule_code VARCHAR(32) NOT NULL,state VARCHAR(16) NOT NULL,
 message VARCHAR(255) NOT NULL,create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),KEY queue_alerts(business_type,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
