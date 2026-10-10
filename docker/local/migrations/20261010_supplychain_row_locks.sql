-- Additive migration before deploying business-row locking.
ALTER TABLE `wms_tickets` MODIFY COLUMN purchase_receipt_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN ticket_type=1 THEN NULLIF(relation_sn,'') ELSE NULL END) STORED COMMENT '入库执行单的到货单唯一关联键；出库单为空';
