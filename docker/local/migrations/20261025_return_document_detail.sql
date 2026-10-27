-- 退货列表仅保存可筛选摘要；报文、回传明细和库存流水按需读取原始记录。
ALTER TABLE oms_return_query_row
 ADD COLUMN warehouse_state VARCHAR(24) NOT NULL DEFAULT 'UNSENT' COMMENT '当前实际仓单受理或截停状态，与最近一次接口成败独立' AFTER dispatched,
 ADD COLUMN interaction_result VARCHAR(16) NOT NULL DEFAULT '' COMMENT '最近一次仓库交互结果，查询失败不覆盖已受理状态' AFTER warehouse_state,
 ADD COLUMN receipt_difference TINYINT NOT NULL DEFAULT 0 COMMENT '仓库已最终回传但本来源尚未收齐，含待核对归属的差异' AFTER interaction_result,
 ADD KEY warehouse_page(company_code,view_kind,warehouse_state,create_time),
 ADD KEY interaction_page(company_code,view_kind,interaction_result,create_time);
