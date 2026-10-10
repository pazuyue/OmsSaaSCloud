CREATE TABLE IF NOT EXISTS channel_platform_app (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '应用配置ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 name VARCHAR(100) NOT NULL COMMENT '应用名称',
 app_key VARCHAR(100) NOT NULL DEFAULT '' COMMENT '天猫开放平台AppKey',
 secret_cipher TEXT COMMENT 'AES-GCM加密的AppSecret，不返回客户端',
 redirect_uri VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'OMS授权结果页面完整HTTPS地址',
 article_code VARCHAR(100) NOT NULL DEFAULT '' COMMENT '服务商品编码',
 item_code VARCHAR(100) NOT NULL DEFAULT '' COMMENT '核验的收费项目编码',
 renewal_url VARCHAR(500) NOT NULL DEFAULT '' COMMENT '淘宝服务市场订购页面',
 enabled TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿或停用，1启用',
 simulation_mode TINYINT NOT NULL DEFAULT 0 COMMENT '0真实平台，1本地模拟；模拟不得发送真实平台请求',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
 KEY idx_company(company_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天猫平台应用配置，按公司隔离';

CREATE TABLE IF NOT EXISTS channel_platform_binding (
 channel_id INT PRIMARY KEY COMMENT 'OMS店铺ID，一店铺一份对接配置',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 app_id BIGINT NOT NULL COMMENT '使用的平台应用',
 expected_shop_id VARCHAR(64) NOT NULL COMMENT '授权前确认的淘宝店铺sid，防止错绑',
 shop_title VARCHAR(200) COMMENT '平台核验的店铺名称',
 seller_id VARCHAR(100) COMMENT '平台授权返回的卖家身份',
 seller_nick VARCHAR(200) COMMENT '授权账号，用于查询服务订购',
 token_cipher TEXT COMMENT 'AES-GCM加密的访问令牌',
 refresh_cipher TEXT COMMENT 'AES-GCM加密的刷新令牌',
 token_expires_at DATETIME COMMENT '平台返回的访问令牌到期时间',
 refresh_expires_at DATETIME COMMENT '平台返回的刷新令牌到期时间，空表示不可刷新',
 auth_status VARCHAR(24) NOT NULL DEFAULT 'UNAUTHORIZED' COMMENT 'UNAUTHORIZED/AUTHORIZED/INVALID/LOCAL_DISABLED',
 service_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN/SUBSCRIBED/UNSUBSCRIBED',
 service_expires_at DATETIME COMMENT '指定收费项目的平台订购截止时间',
 service_checked_at DATETIME COMMENT '最后一次成功查询服务订购的时间',
 reminder_days INT NOT NULL DEFAULT 7 COMMENT '站内提前提醒天数',
 reminder_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否显示站内到期提醒',
 operation_key VARCHAR(32) COMMENT '正在执行的交互标识，防止并发覆盖授权',
 operation_until DATETIME COMMENT '交互锁自动释放时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
 UNIQUE KEY uk_company_shop(company_code,expected_shop_id),
 KEY idx_app(company_code,app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺平台授权及服务订购核验结果';

CREATE TABLE IF NOT EXISTS channel_oauth_state (
 state_hash CHAR(64) PRIMARY KEY COMMENT '随机state的SHA256摘要',
 company_code VARCHAR(64) NOT NULL COMMENT '发起授权的公司',
 channel_id INT NOT NULL COMMENT '发起授权的店铺',
 app_id BIGINT NOT NULL COMMENT '发起授权的应用',
 user_id BIGINT NOT NULL COMMENT '发起授权的OMS用户',
 expires_at DATETIME NOT NULL COMMENT '十分钟后失效',
 consumed TINYINT NOT NULL DEFAULT 0 COMMENT '一次性消费标志',
 KEY idx_expiry(expires_at), KEY idx_channel(company_code,channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OAuth一次性状态，绑定公司、用户、应用和店铺';

CREATE TABLE IF NOT EXISTS channel_interaction_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
 company_code VARCHAR(64) NOT NULL COMMENT '所属公司',
 channel_id INT COMMENT '关联店铺',
 app_id BIGINT COMMENT '关联应用',
 action VARCHAR(80) NOT NULL COMMENT '调用接口或本地配置动作',
 result VARCHAR(24) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/UNKNOWN',
 request_summary TEXT COMMENT '白名单请求摘要，不记录凭据',
 response_summary TEXT COMMENT '白名单结果摘要，不记录原始报文',
 error_code VARCHAR(100) COMMENT '平台错误码或内部分类',
 operator VARCHAR(100) NOT NULL COMMENT '操作人',
 duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '交互耗时毫秒',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求时间',
 KEY idx_company_time(company_code,id), KEY idx_channel(company_code,channel_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台交互及配置审计日志，保留结果和脱敏摘要';
