-- 清单关联状态按公司及条目索引查询；不修改业务单据、库存或历史配置。
-- configure_shared_carriers.install_model 会先检查索引是否存在，支持重复执行。
ALTER TABLE oms_carrier_mapping ADD KEY directory_links(company_code,directory_id,enabled,standard_id) COMMENT '按公司和清单条目查询关联状态，支持分页批量统计';
