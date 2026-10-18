"""Refund goods execution, independent return documents, no business backfill."""
from local_db import execute
from configure_order import column


def install_order(db):
    column(db,'oms_refund','shared_return','TINYINT NOT NULL DEFAULT 0')
    execute("""CREATE TABLE IF NOT EXISTS oms_refund_parcel_config (
      company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
      enabled TINYINT NOT NULL DEFAULT 1, auto_enabled TINYINT NOT NULL DEFAULT 0,
      revision BIGINT NOT NULL DEFAULT 1, policy JSON NOT NULL,
      updated_by VARCHAR(64) NOT NULL, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      PRIMARY KEY(company_code,channel_id), KEY auto_scope(company_code,auto_enabled,channel_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",db)
    for name,definition in {
        'parcel_state':"VARCHAR(24) NOT NULL DEFAULT 'WAIT_LOGISTICS'",
        'parcel_error':"VARCHAR(500) NOT NULL DEFAULT ''",
        'parcel_issue':'JSON NULL','parcel_resolution':"VARCHAR(24) NOT NULL DEFAULT ''",'parcel_manual_dispatch':'TINYINT NOT NULL DEFAULT 0',
        'parcel_next_attempt':'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
        'parcel_lease_token':'VARCHAR(32) NULL','parcel_lease_until':'DATETIME NULL',
        'return_carrier':"VARCHAR(50) NOT NULL DEFAULT ''", 'logistics_source':"VARCHAR(16) NOT NULL DEFAULT ''",
    }.items():column(db,'oms_refund_return_plan',name,definition)
    if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_refund_return_plan' AND index_name='parcel_due' LIMIT 1",db).strip():
        execute('ALTER TABLE oms_refund_return_plan ADD KEY parcel_due(company_code,parcel_next_attempt,id)',db)
    for name, definition in {
        'control_version':'BIGINT NOT NULL DEFAULT 1',
        'waived_quantity':'BIGINT NOT NULL DEFAULT 0',
        'request_key':"VARCHAR(64) NULL", 'reason':"VARCHAR(500) NOT NULL DEFAULT ''",
        'evidence':"VARCHAR(1000) NOT NULL DEFAULT ''", 'actor':"VARCHAR(64) NOT NULL DEFAULT ''",
        'lease_token':'VARCHAR(32) NULL','lease_until':'DATETIME NULL',
        'next_check':'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
    }.items():column(db,'oms_refund_return_plan',name,definition)
    if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_refund_return_plan' AND index_name='request_scope' LIMIT 1",db).strip():
        execute('ALTER TABLE oms_refund_return_plan ADD UNIQUE KEY request_scope(company_code,refund_id,request_key),ADD KEY scan_due(company_code,next_check,id)',db)


def install_supplychain(db):
    from configure_refunds import install_supplychain as base
    base(db)
    column(db,'wms_interaction_log','result_summary',"VARCHAR(1000) NOT NULL DEFAULT ''")
    column(db,'wms_connection','shared_return_waybill','TINYINT NOT NULL DEFAULT 0')
    column(db,'wms_connection','shared_return_append','TINYINT NOT NULL DEFAULT 0')
    column(db,'wms_connection','shared_return_late_append','TINYINT NOT NULL DEFAULT 0')
    execute("""
    CREATE TABLE IF NOT EXISTS wms_refund_parcel (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, parcel_sn VARCHAR(64) NOT NULL,
      carrier_code VARCHAR(64) NOT NULL, waybill VARCHAR(128) NOT NULL, store_code VARCHAR(64) NOT NULL,
      connection_id BIGINT NOT NULL, external_owner VARCHAR(128) NOT NULL, external_warehouse VARCHAR(128) NOT NULL,
      owner_code VARCHAR(64) NOT NULL, real_store_code VARCHAR(64) NOT NULL,
      transport_state VARCHAR(24) NOT NULL DEFAULT 'WAITING', control_version BIGINT NOT NULL DEFAULT 1,
      actor VARCHAR(64) NOT NULL, reason VARCHAR(500) NOT NULL, evidence VARCHAR(1000) NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY sn_scope(company_code,parcel_sn),
      UNIQUE KEY waybill_scope(company_code,carrier_code,waybill), KEY warehouse_scope(company_code,store_code,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS wms_refund_parcel_member (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, parcel_id BIGINT NOT NULL,
      plan_id BIGINT NOT NULL, generation INT NOT NULL, task_id BIGINT NULL,
      refund_id BIGINT NOT NULL, refund_sn VARCHAR(64) NOT NULL, order_sn VARCHAR(64) NOT NULL,
      source_line_id BIGINT NOT NULL, sku_sn VARCHAR(128) NOT NULL, quantity BIGINT NOT NULL,
      actor VARCHAR(64) NOT NULL, reason VARCHAR(500) NOT NULL, evidence VARCHAR(1000) NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY plan_scope(company_code,plan_id,generation), UNIQUE KEY task_scope(company_code,task_id),
      KEY parent(company_code,parcel_id,id), KEY refund_scope(company_code,refund_id,parcel_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS wms_refund_parcel_receipt (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, parcel_id BIGINT NOT NULL,
      message_id VARCHAR(128) NOT NULL, mode VARCHAR(16) NOT NULL, task_id BIGINT NULL,
      payload_hash CHAR(64) NOT NULL, payload JSON NOT NULL, quantity BIGINT NOT NULL,
      state VARCHAR(24) NOT NULL, source_log_id BIGINT NOT NULL, allocation_json JSON NULL,
      actor VARCHAR(64) NOT NULL DEFAULT '', reason VARCHAR(500) NOT NULL DEFAULT '', evidence VARCHAR(1000) NOT NULL DEFAULT '',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY message_scope(company_code,parcel_id,message_id), KEY pending_scope(company_code,parcel_id,state,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,db)
    for name,definition in [('late_append','TINYINT NOT NULL DEFAULT 0'),('append_required','TINYINT NOT NULL DEFAULT 0'),('channel_id','BIGINT NOT NULL DEFAULT 0'),('platform_tid',"VARCHAR(64) NOT NULL DEFAULT ''"),('association_mode',"VARCHAR(16) NOT NULL DEFAULT 'MANUAL'"),('policy_json','JSON NULL')]:column(db,'wms_refund_parcel_member',name,definition)
    column(db,'wms_refund_parcel_member','request_json','JSON NULL')
    column(db,'wms_refund_parcel_member','active','TINYINT NOT NULL DEFAULT 1')
    column(db,'wms_refund_parcel','return_address',"VARCHAR(500) NOT NULL DEFAULT ''")
    column(db,'wms_refund_parcel','return_contact',"VARCHAR(200) NOT NULL DEFAULT ''")
    column(db,'wms_refund_parcel','dispatch_after','DATETIME NULL')
    execute("""CREATE TABLE IF NOT EXISTS wms_refund_parcel_document (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, parcel_id BIGINT NOT NULL,
      return_sn VARCHAR(64) NOT NULL, ticket_id BIGINT NULL, quantity BIGINT NOT NULL,
      payload JSON NOT NULL, attempts INT NOT NULL DEFAULT 0,
      dispatch_state VARCHAR(24) NOT NULL DEFAULT 'PENDING', external_order VARCHAR(100) NOT NULL DEFAULT '',
      warehouse_status VARCHAR(40) NOT NULL DEFAULT '', cancel_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
      control_version BIGINT NOT NULL DEFAULT 1, last_error VARCHAR(500) NOT NULL DEFAULT '',
      lease_token VARCHAR(32) NULL, lease_until DATETIME NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY parcel_scope(company_code,parcel_id), UNIQUE KEY sn_scope(company_code,return_sn)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",db)
    column(db,'wms_refund_parcel_document','generation','INT NOT NULL DEFAULT 1')
    column(db,'wms_refund_parcel_receipt','document_id','BIGINT NULL')
    if execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_refund_parcel_document' AND index_name='parcel_scope' LIMIT 1",db).strip():
        execute('ALTER TABLE wms_refund_parcel_document DROP INDEX parcel_scope, ADD UNIQUE KEY parcel_generation(company_code,parcel_id,generation)',db)
    for name,fields in [('document_pending','company_code,document_id,state,id'),('source_log','company_code,source_log_id,state')]:
        if not execute(f"SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_refund_parcel_receipt' AND index_name='{name}' LIMIT 1",db).strip():execute(f'ALTER TABLE wms_refund_parcel_receipt ADD KEY {name}({fields})',db)
    for name,definition in [('progress_revision','BIGINT NOT NULL DEFAULT 0'),('progress_ack','BIGINT NOT NULL DEFAULT 0'),('notify_pending','TINYINT NOT NULL DEFAULT 0')]:column(db,'wms_refund_return',name,definition)
    for name,fields in [('notify_due','company_code,notify_pending,id'),('ticket_lookup','company_code,ticket_id')]:
        if not execute(f"SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_refund_return' AND index_name='{name}' LIMIT 1",db).strip():execute(f'ALTER TABLE wms_refund_return ADD KEY {name}({fields})',db)


def permissions():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='businessRefunds' AND menu_type='C' LIMIT 1").strip()
    assert parent.isdigit()
    for name,key in [('处理退款货物','goods'),('退款货物差异结案','close')]:
        node=ensure_menu(name,int(parent),'',kind='F',permission='order:refund:'+key,order=30)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{node})')
    from configure_after_sale_rules import menus
    menus()
    execute("UPDATE sys_job SET job_name='天猫退款同步、转单、合包与货物进度扫描',remark='JDP独立游标；按店铺配置自动关联已审退回安排、补偿合包待办及核对仓库进度，不自动审核退款或付款' WHERE invoke_target='refundTask.scanAll()'")


if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_supplychain('qm_oms_saas_commodity')
    permissions()
    print('Refund goods schema and permissions installed; no business documents changed')
