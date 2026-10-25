-- 新增售后工作队列，每类初始并发和预取均为1；保留已有队列的暂停及参数设置。
-- 所有订单队列默认总并发15，实例总预算16，平台访问共用应用和店铺限流。
INSERT IGNORE INTO sys_mq_queue_policy(business_type,concurrency,prefetch) VALUES
 ('REFUND_FLOW',1,1),('REFUND_GOODS',1,1),('REFUND_PLATFORM',1,1),
 ('REFUND_API_PULL',1,1),('REFUND_API_DETAIL',1,1);
