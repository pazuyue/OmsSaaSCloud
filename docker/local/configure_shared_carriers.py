"""Install the new public carrier model. No legacy migration, business backfill or queue changes."""
import sys
from local_db import HERE,execute,quote
from seed_business import ensure_menu


def column(database,table,name,definition):
    if not execute(f"SHOW COLUMNS FROM {table} LIKE '{name}'",database).strip():
        execute(f'ALTER TABLE {table} ADD COLUMN {name} {definition}',database)


def install_model(database):
    execute((HERE/'migrations/20261026_shared_carriers.sql').read_text('utf-8'),database)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_carrier_sync' AND index_name='company_due'",database).strip()=='0':execute("ALTER TABLE oms_carrier_sync ADD KEY company_due(company_code,state,id) COMMENT '每公司独立领取待查询目录任务'",database)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_carrier_mapping' AND index_name='directory_links'",database).strip()=='0':execute("ALTER TABLE oms_carrier_mapping ADD KEY directory_links(company_code,directory_id,enabled,standard_id) COMMENT '按公司和清单条目查询关联状态，支持分页批量统计'",database)
    column(database,'oms_carrier_execution','created_by',"VARCHAR(100) NOT NULL DEFAULT '' COMMENT '本次转换执行的实际操作人或自动流程'")
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_carrier_directory' AND index_name='external_lookup'",database).strip()=='0':execute("ALTER TABLE oms_carrier_directory ADD KEY external_lookup(company_code,system_type,provider,purpose,connection_id,external_code,id) COMMENT '按来源作用域精确查询原始编码'",database)


def install(order,warehouse):
    install_model(order)
    for table in ['oms_order_shipment','oms_order_shipment_package']:
        column(order,table,'standard_id',"BIGINT NULL COMMENT '公共标准快递身份；计划与包裹实际身份分别保存'")
    column(order,'oms_order_shipment','mandated_standard_id',"BIGINT NULL COMMENT '平台明确指定的标准快递身份，仅接入适配器确认后写入，不按名称猜测'")
    column(order,'oms_order_shipment','mandate_evidence',"TEXT NULL COMMENT '平台指定物流的原始正式依据，与计划和仓库实际分别保留'")
    for name,definition in [('carrier_resolution_state',"VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '实际原始物流编码识别结果，未识别不阻断有效出库库存事实'"),('carrier_resolution_json',"TEXT NULL COMMENT '实际快递标准身份、来源连接和映射版本快照'"),('raw_carrier_name',"VARCHAR(200) NOT NULL DEFAULT '' COMMENT '仓库原始快递名称，不用名称推断标准身份'")]:
        column(order,'oms_order_shipment_package',name,definition)
    # All external carrier codes must remain exact strings, including punctuation and leading zero.
    for table in ['oms_order_shipment','oms_order_shipment_package']:
        execute(f"ALTER TABLE {table} MODIFY warehouse_carrier_code VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT 'WMS外部快递编码，精确保留原始字符'",order)
    for table in ['purchase_return','purchase_return_reclaim']:
        if execute(f"SHOW TABLES LIKE '{table}'",warehouse).strip():
            column(warehouse,table,'standard_id',"BIGINT NULL COMMENT '公共标准快递稳定身份，由业务选择器提交'")
            column(warehouse,table,'carrier_snapshot',"TEXT NULL COMMENT '按原连接用途形成的不可变外部编码转换快照'")
    for name,definition in [('raw_carrier_code',"VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '供应商凭证登记的原始快递编码，不作为WMS目标编码使用'"),('raw_carrier_name',"VARCHAR(200) NOT NULL DEFAULT '' COMMENT '供应商原始快递名称，未核实前不代表标准快递'"),('carrier_evidence',"VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '人工核对实际退回标准快递的凭证依据'")]:
        column(warehouse,'purchase_return_reclaim',name,definition)
    for table,field in [('wms_outbound_receipt_package','logistics_code'),('wms_outbound_waybill_lock','logistics_code'),('purchase_return','carrier_code'),('purchase_return_execution','carrier_code'),('purchase_return_parcel','carrier_code'),('purchase_return_reclaim','carrier_code')]:
        if execute(f"SHOW TABLES LIKE '{table}'",warehouse).strip():
            execute(f"ALTER TABLE {table} MODIFY {field} VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '外部原始快递编码，保留大小写前导零和特殊字符'",warehouse)
    execute('''CREATE TABLE IF NOT EXISTS wms_carrier_fact (
      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '原始物流识别任务身份',
      company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '受信公司身份',
      fact_hash CHAR(64) NOT NULL COMMENT '业务及包裹事实去重摘要，同运单不同包裹不合并',
      payload_json TEXT NOT NULL COMMENT '原始物流事实和实际来源连接用途，不含凭证密钥',
      resolution_json TEXT NULL COMMENT '公共服务识别结果及待办身份',
      state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待识别/RESOLVED已识别/WAIT_CONFIG待配置/RETRY服务暂不可用',
      attempts INT NOT NULL DEFAULT 0 COMMENT '服务调用次数',
      next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务异常退避或配置后重查时间',
      last_error VARCHAR(500) NOT NULL DEFAULT '' COMMENT '识别或服务调用异常原因',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '原始事实登记时间',
      UNIQUE KEY original_fact(company_code,fact_hash),KEY due_fact(state,next_attempt,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流识别独立待办，不因服务不可用阻断仓库实收实出记账';''',warehouse)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_carrier_fact' AND index_name='company_due'",warehouse).strip()=='0':execute("ALTER TABLE wms_carrier_fact ADD KEY company_due(company_code,state,next_attempt,id) COMMENT '按公司和退避时间有界扫描物流识别任务'",warehouse)
    for name,definition in [('business_type',"VARCHAR(40) NOT NULL DEFAULT '' COMMENT '物流事实所属业务类型'"),('business_id',"BIGINT NOT NULL DEFAULT 0 COMMENT '业务单据身份，精确限定重试范围'"),('fact_id',"VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '原包裹或物流事实身份，不按运单合并'")]:
        column(warehouse,'wms_carrier_fact',name,definition)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_carrier_fact' AND index_name='business_fact'",warehouse).strip()=='0':execute("ALTER TABLE wms_carrier_fact ADD KEY business_fact(company_code,business_type,business_id,fact_id)",warehouse)
    for name,definition in [('standard_id',"BIGINT NULL COMMENT '本包裹实际快递的已核实标准身份，原始编码独立保留'"),('carrier_resolution_state',"VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '原始物流编码识别状态，未知不阻断有效出库入账'"),('carrier_resolution_json',"TEXT NULL COMMENT '本包裹已核实的来源范围、标准身份及映射版本快照'")]:
        column(warehouse,'purchase_return_parcel',name,definition)
    execute('''CREATE TABLE IF NOT EXISTS wms_carrier_execution (
      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '仓库快递转换执行身份',
      company_code VARCHAR(50) COLLATE utf8mb4_bin NOT NULL COMMENT '受信公司身份',
      request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '原仓库下发请求身份，重试不可重建',
      context_hash CHAR(64) NOT NULL COMMENT '原始物流、连接、货主、实仓及标准身份摘要，禁止改变后重试',
      snapshot_json TEXT NOT NULL COMMENT '首次成功转换的标准身份和WMS外部编码及版本证据',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次转换完成时间',
      UNIQUE KEY original_execution(company_code,request_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库退回单快递转换冻结快照，重试不重新选择编码';''',warehouse)
    column(warehouse,'wms_carrier_execution','context_hash',"CHAR(64) NOT NULL DEFAULT '' COMMENT '原始转换上下文不可变摘要'")


def menus():
    root=ensure_menu('公共配置',0,'common-config',kind='M',order=7)
    parent=ensure_menu('快递管理',root,'carriers',kind='M',order=1)
    for index,(path,component,title) in enumerate([('standards','Standard','标准快递资料'),('channels','Channels','渠道快递配置'),('wms','Wms','WMS 快递配置'),('capabilities','Capabilities','仓库服务能力'),('todos','Todos','快递关联待办')],1):
        menu=ensure_menu(title,parent,path,'oms/common/carriers/'+component,permission='common:carrier:query',order=index)
        execute(f'UPDATE sys_menu SET menu_name={quote(title)} WHERE menu_id={menu}')
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{menu})')
    for action,label in [('query','查询'),('standardEdit','维护标准资料'),('channelEdit','维护平台快递关联'),('wmsEdit','维护仓库快递关联'),('capabilityEdit','维护仓库能力'),('todoHandle','处理待办'),('audit','查询审计')]:
        menu=ensure_menu(label,parent,action,kind='F',permission='common:carrier:'+action)
        execute(f'UPDATE sys_menu SET menu_name={quote(label)} WHERE menu_id={menu}')
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{menu})')
    for menu in [root,parent]:execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{menu})')


if __name__=='__main__':
    assert sys.argv[1:] in (['--tests'],['--local'])
    test=sys.argv[1]=='--tests'
    install('order_workspace_test' if test else 'qm_oms_saas_order','purchase_workspace_test' if test else 'qm_oms_saas_commodity')
    if not test:menus()
    print('Installed public carrier configuration; existing documents, inventory, jobs and queues unchanged')
