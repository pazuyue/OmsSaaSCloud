-- 仅登记新增队列；不修改已有队列状态、并发或全局定时任务。
INSERT IGNORE INTO sys_mq_queue_policy(business_type,desired_mode,concurrency,prefetch,app_rate,shop_rate,updated_by)
VALUES ('EXCHANGE_FLOW','RUNNING',1,1,2,1,'exchange-flow-install');
