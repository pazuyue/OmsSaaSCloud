-- 基础退款能力表尚未创建的环境先执行；开关默认关闭，无业务或凭据回填。
CREATE TABLE IF NOT EXISTS channel_refund_access (
 company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码',
 channel_id BIGINT NOT NULL COMMENT '所属平台店铺渠道编号',
 read_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用普通退款官方查询，默认关闭',
 write_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许平台售后业务操作，独立于实际付款',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '权限设置及授权变更版本，防止并发覆盖',
 review_session_cipher TEXT NULL COMMENT '预留独立审核凭据；当前业务操作使用店铺主授权',
 payment_session_cipher TEXT NULL COMMENT '本店铺付款子账号访问令牌，加密保存',
 updated_by VARCHAR(64) NOT NULL COMMENT '最近修改权限或授权的操作人',
 PRIMARY KEY(company_code,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺退款查询、业务操作与实际付款权限及子账号授权';
CREATE TABLE IF NOT EXISTS channel_refund_sim (
 company_code VARCHAR(50) NOT NULL COMMENT '测试数据所属公司',
 channel_id BIGINT NOT NULL COMMENT '本地模拟平台店铺编号',
 refund_id VARCHAR(64) NOT NULL COMMENT '模拟退款编号，不得当作真实资金依据',
 payload JSON NOT NULL COMMENT '仅TEST模拟接口返回的退款详情原文',
 modified DATETIME NOT NULL COMMENT '模拟退款更新时间，增量窗口与分页校验使用',
 PRIMARY KEY(company_code,channel_id,refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仅本地TEST模拟退款源，非官方平台退款数据';

-- 执行于渠道业务库；主授权与付款子账号授权隔离，默认不启用真实付款。
ALTER TABLE channel_oauth_state
 ADD COLUMN grant_role VARCHAR(24) NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN店铺主授权/REFUND_PAYMENT退款付款子账号授权';

ALTER TABLE channel_refund_access
 ADD COLUMN payment_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许实际退款付款，独立于业务审核操作',
 ADD COLUMN ignore_code TINYINT NOT NULL DEFAULT 0 COMMENT '商家明确开通平台免短信验证码；默认0',
 ADD COLUMN payment_expires_at DATETIME NULL COMMENT '退款子账号访问令牌到期时间，过期必须重新授权',
 ADD COLUMN payment_app_id BIGINT NULL COMMENT '退款子账号令牌所属应用，必须与当前主授权应用一致',
 ADD COLUMN payment_seller_id VARCHAR(100) NULL COMMENT '退款子账号所属主账号，必须与当前店铺主授权一致',
 ADD COLUMN payment_sub_id VARCHAR(100) NOT NULL DEFAULT '' COMMENT '经官方OAuth验证的本店铺付款子账号编号';

CREATE TABLE IF NOT EXISTS channel_refund_command (
      id BIGINT NOT NULL AUTO_INCREMENT COMMENT '平台退款请求主键',
      company_code VARCHAR(50) NOT NULL COMMENT '所属公司编码',
      channel_id BIGINT NOT NULL COMMENT '店铺渠道编号',
      request_id VARCHAR(64) NOT NULL COMMENT '订单服务生成的稳定请求编号，未知结果不得换号重发',
      refund_id VARCHAR(64) NOT NULL COMMENT '平台退款单号，字符串避免大整数丢精度',
      method VARCHAR(128) NOT NULL COMMENT '白名单中的官方接口名称',
      args_hash CHAR(64) NOT NULL COMMENT '原请求参数摘要，禁止同号改写内容',
      state VARCHAR(24) NOT NULL COMMENT 'RUNNING已受理/RECEIVED收到响应/FAILED明确失败/UNKNOWN结果未明',
      response_json JSON NULL COMMENT '原接口响应封装，重入返回原结果，不重复执行',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次受理时间',
      PRIMARY KEY(id),
      UNIQUE KEY request_scope(company_code,channel_id,request_id) COMMENT '按公司店铺及请求编号去重',
      KEY refund_scope(company_code,channel_id,refund_id,id) COMMENT '查询原退款交互记录'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款平台写请求幂等记录，不同退款独立执行';
