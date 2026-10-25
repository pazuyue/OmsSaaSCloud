"""Install documented refund execution schema. Never rewrites business documents."""
from pathlib import Path
import re
from local_db import execute
from configure_order import column


def document_columns(db,table,definitions):
    for name,definition in definitions.items():
        column(db,table,name,definition)
        match=re.search(r"COMMENT '([^']*)'",definition)
        if match:
            old=execute("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' AND COLUMN_NAME='"+name+"'",db).strip()
            if old!=match.group(1):execute('ALTER TABLE '+table+' MODIFY COLUMN '+name+' '+definition,db)


def document_index(db,table,name,columns,comment,unique=False):
    existing=execute("SELECT INDEX_COMMENT FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' AND INDEX_NAME='"+name+"' ORDER BY SEQ_IN_INDEX LIMIT 1",db).strip()
    exists=execute("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' AND INDEX_NAME='"+name+"'",db).strip()!='0'
    if exists and existing==comment:return
    change=('DROP INDEX '+name+', ' if exists else '')+'ADD '+('UNIQUE ' if unique else '')+'INDEX '+name+' ('+columns+") COMMENT '"+comment+"'"
    execute('ALTER TABLE '+table+' '+change+', ALGORITHM=INPLACE, LOCK=NONE',db)


def install_order(db):
    sql=(Path(__file__).parent/'migrations/20261022_refund_execution.sql').read_text(encoding='utf-8')
    table_sql=sql[sql.index('CREATE TABLE'):sql.index("\nALTER TABLE oms_order_line")]
    execute(table_sql,db)
    execute((Path(__file__).parent/'migrations/20261022_refund_flow.sql').read_text(encoding='utf-8'),db)
    definitions={}
    for line in table_sql.splitlines():
        match=re.match(r" ([a-z_]+) (.+ COMMENT '[^']*'),?$",line)
        if match:definitions[match.group(1)]=match.group(2)
    document_columns(db,'oms_refund_goods_task',definitions)
    document_index(db,'oms_order_shipment_line','refund_original_line','company_code,order_line_id,id','退款按原商品行批量查询各仓实发数量')
    document_index(db,'oms_refund_goods_task','return_source','company_code,shipment_line_id,state,id','原发货来源退回额度校验，防止并发售后超占')
    # The existing original_scope already covers the original-suborder budget query.
    # Reuse it instead of adding a duplicate index on every refund write.
    document_columns(db,'oms_refund_flow_policy',{'policy_json':"JSON NOT NULL COMMENT '自动规则JSON：小额免退开关及子单累计上限、发货后剩余全额取消、实收无差异自动结案、平台操作与付款上限；各开关默认关闭'"})
    document_index(db,'oms_refund_operation','execution_scope','company_code,refund_id,action,state,id','检查原平台操作和付款幂等状态')
    document_index(db,'oms_refund_api_item','shop_work','company_code,channel_id,state,id','店铺普通退款详情待处理及失败查询')
    execute("ALTER TABLE oms_refund_goods_task COMMENT='退款按原商品来源拆分的货物任务，支持同单未发取消与已发退回'",db)
    document_columns(db,'oms_platform_refund',{'source_kind':"VARCHAR(24) NOT NULL DEFAULT 'JDP' COMMENT '最近有效来源：JDP推送/ORDINARY_API普通官方接口；不代表专项退款接入'"})
    document_columns(db,'oms_platform_refund_snapshot',{'source_kind':"VARCHAR(24) NOT NULL DEFAULT 'JDP' COMMENT '本次来源记录获取方式：JDP或ORDINARY_API'"})
    document_columns(db,'oms_refund_operation',{
      'action':"VARCHAR(24) NOT NULL COMMENT '平台动作：AGREE_RETURN同意退货/REVIEW审核/PAY付款/PAY_CODE验证码/REFUSE拒绝/其他物流协商动作'",
      'state':"VARCHAR(24) NOT NULL COMMENT 'PENDING待提交/RUNNING提交中/SUCCESS操作返回成功/FAILED明确失败/UNKNOWN待核对/ACCEPTED受理/CLOSED结束；SUCCESS不等于资金到账'",
      'fee':"DECIMAL(20,2) NOT NULL COMMENT '本次平台退款申请金额，单位元；接口按分换算，不代表实际付款成功金额'",
      'policy_revision':"BIGINT NULL COMMENT '采用的店铺自动流程规则版本，人工操作为空；发出前再次核对启用状态及版本'",
      'approved_hash':"CHAR(64) NULL COMMENT '操作采用的已审核退款内容摘要；用于付款审核关联'",
      'request_json':"JSON NULL COMMENT '冻结的平台业务参数，不保存短信验证码或账号令牌'",
      'response_json':"JSON NULL COMMENT '平台原操作响应；与最终资金成功事实分开核对'",
      'lease_until':"DATETIME NULL COMMENT '提交租约到期时间，超时后只查询原结果'"
    })
    document_columns(db,'oms_refund',{
      'platform_context':"JSON NULL COMMENT '最近平台能力与可用操作缓存；列表和详情不逐条请求平台'",
      'platform_checked_at':"DATETIME NULL COMMENT '最近一次主动查询平台操作能力的时间'"
    })
    document_columns(db,'oms_order_line',{'refund_block_reason':"VARCHAR(500) NOT NULL DEFAULT '' COMMENT '本实际商品行的退款履约阻塞原因；不影响其他子单，分仓与下发共同校验'"})
    document_columns(db,'oms_refund',{
      'initial_fulfillment_stage':"VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' COMMENT '首次转单实际履约阶段：UNSHIPPED未出库/PART_SHIPPED部分出库/SHIPPED全部出库/CANCELED全取消/UNKNOWN待核对'",
      'initial_fulfillment_snapshot':"JSON NULL COMMENT '首次转单原商品与各仓履约快照，只写一次，不用当前状态覆盖'"
    })


def install_channel(db):
    from configure_refunds import install_channel as base
    base(db)
    document_columns(db,'channel_refund_access',{
      'company_code':"VARCHAR(50) NOT NULL COMMENT '所属公司编码'",
      'channel_id':"BIGINT NOT NULL COMMENT '所属平台店铺渠道编号'",
      'read_enabled':"TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用普通退款官方查询，默认关闭'",
      'write_enabled':"TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许平台售后业务操作，独立于实际付款'",
      'revision':"BIGINT NOT NULL DEFAULT 1 COMMENT '权限设置及子账号授权变更版本，防止并发覆盖'",
      'review_session_cipher':"TEXT NULL COMMENT '预留独立审核凭据；当前业务操作使用店铺主授权'",
      'payment_session_cipher':"TEXT NULL COMMENT '官方OAuth验证的本店铺付款子账号访问令牌，加密保存'",
      'updated_by':"VARCHAR(64) NOT NULL COMMENT '最近修改权限或授权的操作人'"
    })
    execute("ALTER TABLE channel_refund_access COMMENT='店铺退款查询、业务操作与实际付款权限及子账号授权'",db)
    document_columns(db,'channel_refund_sim',{
      'company_code':"VARCHAR(50) NOT NULL COMMENT '测试数据所属公司'",
      'channel_id':"BIGINT NOT NULL COMMENT '本地模拟平台店铺编号'",
      'refund_id':"VARCHAR(64) NOT NULL COMMENT '模拟退款编号，不得当作真实资金依据'",
      'payload':"JSON NOT NULL COMMENT '仅TEST模拟接口返回的退款详情原文'",
      'modified':"DATETIME NOT NULL COMMENT '模拟退款更新时间，增量窗口与分页校验使用'"
    })
    execute("ALTER TABLE channel_refund_sim COMMENT='仅本地TEST模拟退款源，非官方平台退款数据'",db)
    document_columns(db,'channel_oauth_state',{'grant_role':"VARCHAR(24) NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN店铺主授权/REFUND_PAYMENT退款付款子账号授权'"})
    document_columns(db,'channel_refund_access',{
      'payment_enabled':"TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许实际退款付款，独立于业务审核操作'",
      'ignore_code':"TINYINT NOT NULL DEFAULT 0 COMMENT '商家明确开通平台免短信验证码；默认0'",
      'payment_expires_at':"DATETIME NULL COMMENT '退款子账号访问令牌到期时间，过期必须重新授权'",
      'payment_app_id':"BIGINT NULL COMMENT '退款子账号令牌所属应用，必须与当前主授权应用一致'",
      'payment_seller_id':"VARCHAR(100) NULL COMMENT '退款子账号所属主账号，必须与当前店铺主授权一致'",
      'payment_sub_id':"VARCHAR(100) NOT NULL DEFAULT '' COMMENT '经官方OAuth验证的本店铺付款子账号编号'"
    })
    execute("""CREATE TABLE IF NOT EXISTS channel_refund_command (
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
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款平台写请求幂等记录，不同退款独立执行'""",db)
    # Existing installations receive comment corrections only; no credential or business data is read.
    for name,definition in {
      'id':"BIGINT NOT NULL AUTO_INCREMENT COMMENT '平台退款请求主键'",
      'company_code':"VARCHAR(50) NOT NULL COMMENT '所属公司编码'",
      'channel_id':"BIGINT NOT NULL COMMENT '店铺渠道编号'",
      'request_id':"VARCHAR(64) NOT NULL COMMENT '稳定请求编号，未知结果不得换号重发'",
      'refund_id':"VARCHAR(64) NOT NULL COMMENT '平台退款单号，字符串避免大整数丢精度'",
      'method':"VARCHAR(128) NOT NULL COMMENT '白名单中的官方接口名称'",
      'args_hash':"CHAR(64) NOT NULL COMMENT '原参数摘要，禁止同号改写内容'",
      'state':"VARCHAR(24) NOT NULL COMMENT 'RUNNING已受理/RECEIVED收到响应/FAILED明确失败/UNKNOWN结果未明'",
      'response_json':"JSON NULL COMMENT '原接口响应封装，重入返回原结果，不重复执行'",
      'create_time':"DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次受理时间'"
    }.items():document_columns(db,'channel_refund_command',{name:definition})


if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_channel('qm_oms_saas_channel')
    execute((Path(__file__).parent/'migrations/20261022_refund_queues.sql').read_text(encoding='utf-8'))
    print('Refund execution schema installed; no business documents modified')
