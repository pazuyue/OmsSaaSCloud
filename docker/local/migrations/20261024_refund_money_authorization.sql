-- 差价业务授权独立于退款审核与付款；不补写历史退款、不启用任何自动规则。
CREATE TABLE IF NOT EXISTS oms_refund_money_authorization (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '差价业务授权主键',
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码，所有读取和变更必须带公司范围',
 channel_id BIGINT NOT NULL COMMENT '授权所属平台店铺渠道编号',
 platform_tid VARCHAR(64) NOT NULL COMMENT '授权对应原平台主订单号，不跨订单使用',
 platform_oid VARCHAR(64) NOT NULL COMMENT '授权对应原平台子订单号，不跨商品使用',
 refund_id VARCHAR(64) NOT NULL COMMENT '唯一获授权的平台退款号；字符串保留大整数精度',
 amount DECIMAL(20,2) NOT NULL COMMENT '明确授权的差价金额，单位元，须与平台本次申请完全一致',
 purpose VARCHAR(24) NOT NULL DEFAULT 'DIFFERENCE' COMMENT '本授权仅支持DIFFERENCE差价，禁止从金额或原因关键词推断',
 reference_sn VARCHAR(100) NOT NULL COMMENT '商家差价协议或业务凭证编号，供人工追溯',
 evidence VARCHAR(1000) NOT NULL COMMENT '差价业务依据，由具备配置权限的商家明确登记',
 state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE待采用/USED已被指定业务退款采用/REVOKED采用前撤销',
 used_refund_id BIGINT NULL COMMENT '采用该授权的业务退款主键，只能被同一退款使用',
 expires_at DATETIME NOT NULL COMMENT '授权到期时间，过期授权不能首次采用或重审采用',
 authorized_by VARCHAR(64) NOT NULL COMMENT '预先登记差价授权的操作人，不作为退款审核或付款执行人',
 used_at DATETIME NULL COMMENT '系统自动审核采用授权的时间，与授权登记时间分开记录',
 revoked_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '采用前撤销授权的操作人',
 control_version BIGINT NOT NULL DEFAULT 1 COMMENT '授权变更版本，用于并发撤销保护',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '差价授权登记时间',
 PRIMARY KEY(id),
 UNIQUE KEY target_scope(company_code,channel_id,refund_id) COMMENT '同公司同店铺同平台退款只允许一份不可改写的差价授权',
 KEY shop_scope(company_code,channel_id,id) COMMENT '店铺配置页有界查询授权记录'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='明确凭证及指定退款号的差价预授权；不代表已审核退款或已付款';
