CREATE TABLE IF NOT EXISTS oms_allocation_stock_signal (
 company_code VARCHAR(50) NOT NULL,
 sku_sn VARCHAR(100) NOT NULL,
 store_code VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '事务内递增；确认时校验，防止丢失后续补货',
 acknowledged_revision BIGINT NOT NULL DEFAULT 0 COMMENT '订单服务已持久化唤醒的版本',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,sku_sn,store_code),
 KEY idx_stock_signal_time(company_code,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可用库存增加信号，提交后用于唤醒相关自动分仓订单';
