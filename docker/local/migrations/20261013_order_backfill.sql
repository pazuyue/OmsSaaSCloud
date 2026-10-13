CREATE TABLE IF NOT EXISTS oms_order_pull_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 task_id BIGINT NOT NULL, platform_tid VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
 platform_order_id BIGINT NULL,
 result VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 message VARCHAR(500) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY pull_item(company_code,task_id,platform_tid),
 KEY pull_item_result(company_code,task_id,result,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
