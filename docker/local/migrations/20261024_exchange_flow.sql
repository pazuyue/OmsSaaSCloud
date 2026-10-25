CREATE TABLE IF NOT EXISTS oms_exchange_flow_policy (
  company_code VARCHAR(50) NOT NULL COMMENT '所属公司，和店铺共同限定规则范围',
  channel_id BIGINT NOT NULL COMMENT '所属店铺，不跨店采用自动规则',
  enabled TINYINT NOT NULL DEFAULT 0 COMMENT '自动换货总开关：0关闭，1启用；平台及仓库节点另有独立开关',
  revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每个新增自动动作重新校验当前规则版本',
  enabled_after DATETIME NULL COMMENT '首次启用时间；仅自动接管此后创建的新HH，不回填或批量推进历史单',
  policy_json JSON NOT NULL COMMENT '适用商品和SKU映射、免补差依据、仓库、各自动节点及等待期限',
  updated_by VARCHAR(64) NOT NULL COMMENT '最后维护规则的商家账号',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY(company_code,channel_id),
  KEY enabled_scope(company_code,enabled,channel_id) COMMENT '按公司读取已启用店铺，不全表扫描业务单'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='换货自动规则：商家业务授权与平台接口权限分别管理';

CREATE TABLE IF NOT EXISTS oms_exchange_flow_task (
  company_code VARCHAR(50) NOT NULL COMMENT '所属公司，任务与HH必须属于同公司',
  exchange_id BIGINT NOT NULL COMMENT '原HH业务换货ID；不创建新SO',
  work_version BIGINT NOT NULL DEFAULT 1 COMMENT '消息代次；旧版本消息不再发起新业务动作',
  observed_version BIGINT NOT NULL DEFAULT 0 COMMENT '最近已接收的HH控制版本；相同通知不重复入队',
  policy_revision BIGINT NULL COMMENT '本次判定采用的店铺自动规则版本',
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待执行、WAITING等待事实、MANUAL人工处理、HELD暂停、DONE本地及平台均完成',
  node VARCHAR(40) NOT NULL DEFAULT 'REVIEW' COMMENT '当前自动节点：方案、库存、平台、退回、验收、换出或完成核对',
  last_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '稳定阻塞原因码，用于页面分类及人工处理入口',
  last_message VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '具体等待或人工处理原因，不将转人工等同审核放行',
  wait_since DATETIME NULL COMMENT '当前节点开始等待时间；重复通知不延长业务期限',
  next_attempt DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次允许评估时间；使用持久化待办，不sleep占用线程',
  lease_token VARCHAR(32) NULL COMMENT '本次推进租约，防止不同消息代次并发执行同一HH',
  lease_until DATETIME NULL COMMENT '租约到期时间；到期只核对已发命令，不重复执行未知结果',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次纳入自动流程时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近推进时间',
  PRIMARY KEY(company_code,exchange_id),
  KEY due(company_code,state,next_attempt,exchange_id) COMMENT '只扫描当前公司到期的小批待办'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='换货自动推进任务：一张HH独立串行，按原轮次继续执行';

CREATE TABLE IF NOT EXISTS oms_exchange_operation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '换货操作记录ID',
  company_code VARCHAR(50) NOT NULL COMMENT '所属公司',
  exchange_id BIGINT NOT NULL COMMENT '所属HH业务换货ID',
  round_no INT NOT NULL DEFAULT 1 COMMENT '换出轮次；不同轮次不复用库存或发货命令',
  action VARCHAR(40) NOT NULL COMMENT '操作节点：AGREE、RECEIVED、CONSIGN、自动验收或仓库动作等',
  command_key CHAR(64) NOT NULL COMMENT '公司、HH、轮次、方案及规则版本的命令摘要；同一命令幂等',
  request_id VARCHAR(80) NOT NULL COMMENT '稳定请求号；响应未知时沿原请求查询',
  source_version BIGINT NOT NULL COMMENT '发起时HH控制版本；状态同步不能改写原采用方案',
  policy_revision BIGINT NOT NULL COMMENT '明确授权此动作的店铺自动规则版本',
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待执行、SENDING提交中、UNKNOWN结果不明、SUCCESS已确认、FAILED明确失败、CLOSED已停止',
  request_json JSON NOT NULL COMMENT '不可变动作范围和业务依据，不保存接口密钥',
  result_json JSON NULL COMMENT '原请求执行或查询得到的结果快照',
  message VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '执行或查询结果说明',
  actor VARCHAR(64) NOT NULL COMMENT '执行者；商家授权、自动审核及自动执行分别记录',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '动作登记时间，先落地后跨服务提交',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近确认时间',
  UNIQUE KEY command_scope(company_code,exchange_id,command_key) COMMENT '同一自动业务命令只登记一次',
  UNIQUE KEY request_scope(company_code,request_id) COMMENT '请求号公司内唯一',
  KEY parent(company_code,exchange_id,id) COMMENT '按HH批量查询操作记录',
  KEY pending(company_code,state,update_time,id) COMMENT '按公司核对未知操作'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='换货自动操作历史：平台请求与实际收发结果单独核对';
