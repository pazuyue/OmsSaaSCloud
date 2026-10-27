-- 新公共快递模型；不迁移旧配置，不改库存和存量业务单据。
CREATE TABLE IF NOT EXISTS oms_carrier_standard (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 standard_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '公司内唯一OMS标准编码，创建后不可修改',
 standard_name VARCHAR(100) NOT NULL COMMENT '承运商标准名称，不是渠道名称或运输产品',
 aliases_json TEXT NOT NULL COMMENT '搜索别名JSON数组，仅辅助搜索，不作为正式映射依据',
 category VARCHAR(16) NOT NULL COMMENT 'EXPRESS快递/FREIGHT快运/OTHER其他',
 enabled TINYINT NOT NULL DEFAULT 1 COMMENT '启用1/停用0；停用命中不可降级回退',
 disabled_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '停用原因，供业务选择与异常解释',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发编辑版本，保存必须匹配原版本',
 updated_by VARCHAR(100) NOT NULL COMMENT '最近修改人',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
 UNIQUE KEY company_code_identity(company_code,standard_code), KEY company_name(company_code,standard_name,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司公共标准快递资料，独立于编码对照和仓库服务能力';

CREATE TABLE IF NOT EXISTS oms_carrier_directory (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 system_type VARCHAR(10) COLLATE utf8mb4_bin NOT NULL COMMENT '外部系统：CHANNEL渠道或WMS仓储',
 provider VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '平台TMALL/JD/DOUYIN或WMS协议供应商',
 purpose VARCHAR(40) COLLATE utf8mb4_bin NOT NULL COMMENT '接口用途，发货回传、售后寄回、WMS出入库分别配置',
 connection_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '连接实例；WMS必填，渠道为空表示平台范围',
 shop_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '渠道店铺身份，为空表示连接或平台范围',
 owner_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'OMS货主编码，为空表示WMS连接范围',
 real_store_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'OMS实仓编码；填写时必须同时指定货主',
 external_code VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '外部原始编码，区分大小写且不得转换数值',
 external_name VARCHAR(200) NOT NULL COMMENT '外部目录原始名称',
 source VARCHAR(20) NOT NULL COMMENT 'MANUAL人工/IMPORT文件/API接口',
 evidence VARCHAR(1000) NOT NULL COMMENT '官方文档、仓库书面确认或原始目录来源',
 verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本条目录最近核实时间',
 scope_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '系统与用途及作用域摘要，精确去重',
 enabled TINYINT NOT NULL DEFAULT 1 COMMENT '启用1/停用0；停用命中不可降级回退',
 disabled_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '停用原因，供业务选择与异常解释',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发编辑版本，保存必须匹配原版本',
 updated_by VARCHAR(100) NOT NULL COMMENT '最近修改人',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
 UNIQUE KEY directory_identity(company_code,scope_hash,external_code), KEY lookup(company_code,system_type,provider,purpose,connection_id,id), KEY external_lookup(company_code,system_type,provider,purpose,connection_id,external_code,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部快递目录，保留大小写前导零和特殊字符，存在不代表可发货';

CREATE TABLE IF NOT EXISTS oms_carrier_mapping (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 system_type VARCHAR(10) COLLATE utf8mb4_bin NOT NULL COMMENT '外部系统：CHANNEL渠道或WMS仓储',
 provider VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '平台TMALL/JD/DOUYIN或WMS协议供应商',
 purpose VARCHAR(40) COLLATE utf8mb4_bin NOT NULL COMMENT '接口用途，发货回传、售后寄回、WMS出入库分别配置',
 connection_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '连接实例；WMS必填，渠道为空表示平台范围',
 shop_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '渠道店铺身份，为空表示连接或平台范围',
 owner_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'OMS货主编码，为空表示WMS连接范围',
 real_store_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'OMS实仓编码；填写时必须同时指定货主',
 directory_id BIGINT NOT NULL COMMENT '外部目录身份，必须同公司且作用域适用',
 standard_id BIGINT NOT NULL COMMENT 'OMS标准快递稳定身份，禁止使用自由文本代替',
 direction VARCHAR(8) NOT NULL COMMENT 'IN外部识别/OUT目标转换/BOTH双向',
 product_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '运输产品条件，非承运商身份',
 is_default TINYINT NOT NULL DEFAULT 0 COMMENT '同作用域同标准的唯一默认输出1',
 evidence VARCHAR(1000) NOT NULL COMMENT '正式人工确认的对照依据',
 enabled TINYINT NOT NULL DEFAULT 1 COMMENT '启用1/停用0；停用命中不可降级回退',
 disabled_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '停用原因，供业务选择与异常解释',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发编辑版本，保存必须匹配原版本',
 updated_by VARCHAR(100) NOT NULL COMMENT '最近修改人',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
 KEY source_lookup(company_code,system_type,provider,purpose,connection_id,directory_id), KEY target_lookup(company_code,standard_id,system_type,provider,purpose,connection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准快递与渠道WMS编码对照，输入输出方向和用途分别校验';

CREATE TABLE IF NOT EXISTS oms_carrier_capability (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 connection_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'WMS实际连接实例，必须属于当前公司',
 owner_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'OMS货主编码',
 real_store_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'OMS实体仓编码',
 standard_id BIGINT NOT NULL COMMENT 'OMS标准快递身份',
 flow VARCHAR(10) COLLATE utf8mb4_bin NOT NULL COMMENT 'OUTBOUND发货或INBOUND收货',
 product_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '服务支持的运输产品，空为默认',
 provinces_json TEXT NOT NULL COMMENT '出库允许省份JSON；入库用途不应用此限制',
 excluded_provinces_json TEXT NOT NULL COMMENT '出库排除省份JSON，与允许范围互斥',
 evidence VARCHAR(1000) NOT NULL COMMENT '仓库能力确认依据，不代表电子面单签约',
 enabled TINYINT NOT NULL DEFAULT 1 COMMENT '启用1/停用0；停用命中不可降级回退',
 disabled_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '停用原因，供业务选择与异常解释',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发编辑版本，保存必须匹配原版本',
 updated_by VARCHAR(100) NOT NULL COMMENT '最近修改人',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
 UNIQUE KEY service_identity(company_code,connection_id,owner_code,real_store_code,standard_id,flow,product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库收发服务能力，与目录对照及商家电子面单合同相互独立';

CREATE TABLE IF NOT EXISTS oms_carrier_revision (
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY COMMENT '受信公司身份', revision BIGINT NOT NULL DEFAULT 0 COMMENT '配置变更递增版本，用于预览确认与缓存失效'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司快递配置写入互斥和整体版本，避免并发配置制造重复映射';

CREATE TABLE IF NOT EXISTS oms_carrier_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 object_type VARCHAR(32) NOT NULL COMMENT '对象类型STANDARD/DIRECTORY/MAPPING/CAPABILITY/TODO/IMPORT/SYNC',
 object_id BIGINT NOT NULL COMMENT '操作对象身份',
 action VARCHAR(32) NOT NULL COMMENT '操作类型',
 before_json MEDIUMTEXT NOT NULL COMMENT '变更前完整配置，审计不可覆盖',
 after_json MEDIUMTEXT NOT NULL COMMENT '变更后完整配置或操作结果',
 actor VARCHAR(100) NOT NULL COMMENT '实际操作人',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
 KEY audit_page(company_code,id), KEY object_history(company_code,object_type,object_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公共快递操作审计，记录编辑导入确认及待办重验重试';

CREATE TABLE IF NOT EXISTS oms_carrier_execution (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '业务提供的幂等执行请求身份',
 context_json TEXT NOT NULL COMMENT '不可变业务上下文含连接货主实仓店铺用途产品',
 context_hash CHAR(64) NOT NULL COMMENT '不可变上下文内容摘要，重复请求变更内容拒绝',
 snapshot_json TEXT NOT NULL COMMENT '标准身份外部编码映射版本及证据快照',
 created_by VARCHAR(100) NOT NULL DEFAULT '' COMMENT '生成本次转换执行的实际操作人或自动流程',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行形成时间',
 UNIQUE KEY immutable_request(company_code,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变编码转换执行快照，重试沿用相同请求身份及原编码';

CREATE TABLE IF NOT EXISTS oms_carrier_todo (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 fact_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '同业务事实及用途和错误类型去重摘要',
 business_type VARCHAR(40) NOT NULL COMMENT 'SHIPMENT/PURCHASE_RETURN/REFUND/EXCHANGE/RECLAIM等',
 business_id BIGINT NOT NULL DEFAULT 0 COMMENT '本地业务身份，重试时重新校验所属公司',
 business_sn VARCHAR(100) NOT NULL COMMENT '原始业务单号',
 fact_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '包裹或原始物流事实身份，不按运单号合并',
 blocked_action VARCHAR(40) NOT NULL COMMENT '未完成动作，如PLATFORM_SHIPPING/OUTBOUND_DISPATCH/RESOLVE_ACTUAL',
 reason_code VARCHAR(40) NOT NULL COMMENT '缺失冲突停用等结构化原因',
 reason VARCHAR(1000) NOT NULL COMMENT '面向操作人的具体阻断原因',
 context_json TEXT NOT NULL COMMENT '原始编码名称及完整转换作用域，不覆盖实际物流',
 state VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN待修复/READY校验通过/COMPLETED业务完成；修复不代表完成',
 occurrences BIGINT NOT NULL DEFAULT 1 COMMENT '相同事实重复发现次数',
 revision BIGINT NOT NULL DEFAULT 1 COMMENT '待办操作并发版本',
 updated_by VARCHAR(100) NOT NULL COMMENT '最近检查或操作人',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次发现时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近发现或操作时间',
 UNIQUE KEY fact_identity(company_code,fact_hash), KEY todo_page(company_code,state,id), KEY business_todo(company_code,business_type,business_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编码异常待办，仅重试未完成步骤，不改变原业务商品或库存';

CREATE TABLE IF NOT EXISTS oms_carrier_import (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 request_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端生成预览确认身份，确认可幂等重复',
 base_revision BIGINT NOT NULL COMMENT '预览时公司配置版本，期间变更须重新预览',
 payload_json MEDIUMTEXT NOT NULL COMMENT '含作用域和逐行新增更新重复错误结果',
 state VARCHAR(16) NOT NULL DEFAULT 'PREVIEW' COMMENT 'PREVIEW待确认/CONFIRMED已确认',
 created_by VARCHAR(100) NOT NULL COMMENT '预览操作人，仅原操作人可确认',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预览创建时间，24小时失效',
 UNIQUE KEY import_request(company_code,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目录文件导入预览确认，保存精确字符串和原配置版本';

CREATE TABLE IF NOT EXISTS oms_carrier_sync (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系统生成稳定身份',
 company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '公司隔离身份，仅取登录或受信内部上下文',
 system_type VARCHAR(10) COLLATE utf8mb4_bin NOT NULL COMMENT '外部系统：CHANNEL渠道或WMS仓储',
 provider VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '平台TMALL/JD/DOUYIN或WMS协议供应商',
 purpose VARCHAR(40) COLLATE utf8mb4_bin NOT NULL COMMENT '接口用途，发货回传、售后寄回、WMS出入库分别配置',
 connection_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '连接实例；WMS必填，渠道为空表示平台范围',
 shop_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '渠道店铺身份，为空表示连接或平台范围',
 owner_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'OMS货主编码，为空表示WMS连接范围',
 real_store_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'OMS实仓编码；填写时必须同时指定货主',
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING排队/RUNNING执行/SUCCESS成功/FAILED失败/UNSUPPORTED未接通',
 source_url VARCHAR(500) NOT NULL DEFAULT '' COMMENT '官方接口文档来源，非任意HTTP抓取地址',
 result_json MEDIUMTEXT NOT NULL COMMENT '目录新增改名停用差异及失败原因',
 actor VARCHAR(100) NOT NULL COMMENT '触发人',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务提交时间',
 finish_time DATETIME NULL COMMENT '任务结束时间',
 KEY due_sync(state,id), KEY company_due(company_code,state,id), KEY sync_page(company_code,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力受控的后台目录同步任务，失败不删除或清空已有目录及对照';
