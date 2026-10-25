"""Exchange workflow schema. No historical document changes or existing job switch changes."""
from configure_order import column
from local_db import execute


def install_order(db):
    from configure_exchange_automation import install_order as install_flow
    install_flow(db)
    execute("""CREATE TABLE IF NOT EXISTS oms_exchange_config (
      company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
      enabled TINYINT NOT NULL DEFAULT 0, write_enabled TINYINT NOT NULL DEFAULT 0,
      initial_time DATETIME NOT NULL, cursor_time DATETIME NOT NULL,
      revision BIGINT NOT NULL DEFAULT 1, next_run DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
      last_error VARCHAR(500) NOT NULL DEFAULT '', updated_by VARCHAR(64) NOT NULL,
      PRIMARY KEY(company_code,channel_id), KEY due(company_code,enabled,next_run)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_exchange_sync (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
      mode VARCHAR(12) NOT NULL, start_time DATETIME NOT NULL, end_time DATETIME NOT NULL,
      page_no INT NOT NULL DEFAULT 1, dispute_ids JSON NULL, state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
      attempts INT NOT NULL DEFAULT 0, synced_count INT NOT NULL DEFAULT 0,
      lease_token VARCHAR(32) NULL, lease_until DATETIME NULL, next_attempt DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
      last_error VARCHAR(500) NOT NULL DEFAULT '', actor VARCHAR(64) NOT NULL,
      request_key VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY request_scope(company_code,request_key,channel_id), KEY due(company_code,state,next_attempt),
      KEY shop_scope(company_code,channel_id,state,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_exchange (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
      exchange_sn VARCHAR(64) NOT NULL, source_kind VARCHAR(16) NOT NULL,
      dispute_id VARCHAR(64) NULL, source_phase VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
      platform_status VARCHAR(64) NOT NULL DEFAULT '', goods_status VARCHAR(64) NOT NULL DEFAULT '',
      speed_exchange TINYINT NULL, platform_tid VARCHAR(64) NOT NULL DEFAULT '', platform_oid VARCHAR(64) NOT NULL DEFAULT '',
      source_modified DATETIME NOT NULL, payload_hash CHAR(64) NOT NULL, payload JSON NOT NULL,
      requested_quantity BIGINT NOT NULL DEFAULT 0, original_sku VARCHAR(128) NOT NULL DEFAULT '',
      target_sku VARCHAR(128) NOT NULL DEFAULT '', target_description VARCHAR(1000) NOT NULL DEFAULT '',
      order_id BIGINT NULL, order_line_id BIGINT NULL, fulfillment_order_id BIGINT NULL,
      original_platform_order_id BIGINT NULL, state VARCHAR(24) NOT NULL DEFAULT 'NEEDS_REVIEW',
      stage VARCHAR(32) NOT NULL DEFAULT 'MATCH', problem_codes VARCHAR(500) NOT NULL DEFAULT '',
      problem_message VARCHAR(1000) NOT NULL DEFAULT '', control_version BIGINT NOT NULL DEFAULT 1,
      approved_version BIGINT NULL, approved_snapshot JSON NULL, review_by VARCHAR(64) NOT NULL DEFAULT '',
      review_reason VARCHAR(500) NOT NULL DEFAULT '', reviewed_at DATETIME NULL,
      platform_operation VARCHAR(24) NOT NULL DEFAULT 'NONE', platform_request JSON NULL,
      platform_operation_version BIGINT NULL, platform_error VARCHAR(500) NOT NULL DEFAULT '',
      cutoff_id BIGINT NULL, return_sn VARCHAR(64) NOT NULL DEFAULT '', return_store VARCHAR(64) NOT NULL DEFAULT '',
      platform_address_id VARCHAR(64) NOT NULL DEFAULT '', platform_deadline DATETIME NULL,
      stock_request JSON NULL, stock_receipt JSON NULL, last_error VARCHAR(500) NOT NULL DEFAULT '',
      next_attempt DATETIME NULL, lease_token VARCHAR(32) NULL, lease_until DATETIME NULL,
      actor VARCHAR(64) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY platform_scope(company_code,channel_id,dispute_id), UNIQUE KEY sn_scope(company_code,exchange_sn),
      KEY work_due(company_code,state,next_attempt), KEY order_scope(company_code,order_id,id), KEY fulfillment_scope(company_code,fulfillment_order_id,id),
      KEY list_scope(company_code,state,id), KEY oid_scope(company_code,channel_id,platform_oid,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_exchange_event (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, exchange_id BIGINT NOT NULL,
      action VARCHAR(40) NOT NULL, state VARCHAR(24) NOT NULL, version BIGINT NOT NULL,
      message VARCHAR(1000) NOT NULL, evidence JSON NULL, actor VARCHAR(64) NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY parent(company_code,exchange_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_exchange_snapshot (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, exchange_id BIGINT NOT NULL,
      source_modified DATETIME NOT NULL, payload_hash CHAR(64) NOT NULL, payload JSON NOT NULL,
      outcome VARCHAR(24) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY source_version(company_code,exchange_id,source_modified,payload_hash), KEY history(company_code,exchange_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_exchange_update_link (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, exchange_id BIGINT NOT NULL,
      platform_order_id BIGINT NOT NULL, update_id BIGINT NOT NULL, source_hash CHAR(64) NOT NULL,
      field_path VARCHAR(150) NOT NULL, difference_hash CHAR(64) NOT NULL, difference JSON NOT NULL,
      state VARCHAR(24) NOT NULL DEFAULT 'LINKED', applied_at DATETIME NULL,
      UNIQUE KEY coverage(company_code,update_id,difference_hash), KEY application(company_code,exchange_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)
    for name, definition in {
        'business_type': "VARCHAR(24) NOT NULL DEFAULT 'SALE'",
        'exchange_id': 'BIGINT NULL', 'source_order_id': 'BIGINT NULL',
        'source_platform_order_id': 'BIGINT NULL', 'stock_reference': "VARCHAR(64) NOT NULL DEFAULT ''",
    }.items():
        column(db, 'oms_order', name, definition)
    column(db, 'oms_order_shipment', 'stock_reference', "VARCHAR(64) NOT NULL DEFAULT ''")
    for table in ['oms_order_line', 'oms_order_shipment']:
        column(db, table, 'business_type', "VARCHAR(24) NOT NULL DEFAULT 'SALE'")
        column(db, table, 'exchange_id', 'BIGINT NULL')
    for name, definition in {'stock_reference': "VARCHAR(64) NOT NULL DEFAULT ''", 'source_line_id': 'BIGINT NULL', 'source_shipment_line_id': 'BIGINT NULL'}.items():
        column(db, 'oms_order_line', name, definition)
    fields = execute("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_order_line' AND index_name='uk_order_line'", db).strip()
    if fields and 'stock_reference' not in fields:
        execute('ALTER TABLE oms_order_line DROP INDEX uk_order_line, ADD UNIQUE KEY uk_order_line(order_id,platform_oid,stock_reference)', db)
    for table, name, fields in [('oms_order_line', 'fulfillment_scope', 'company_code,order_id,business_type,stock_reference,id'), ('oms_order_shipment','exchange_owner','company_code,exchange_id,business_type,id')]:
        if not execute(f"SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}' LIMIT 1", db).strip():
            execute(f'ALTER TABLE {table} ADD KEY {name}({fields})', db)
    for name, definition in {'fulfillment_line_id': 'BIGINT NULL', 'source_shipment_line_id': 'BIGINT NULL', 'allocation_status': "VARCHAR(24) NOT NULL DEFAULT 'NONE'", 'allocation_quantity': 'BIGINT NOT NULL DEFAULT 0', 'allocation_receipts': 'JSON NULL', 'allocation_policy': 'JSON NULL'}.items():
        column(db, 'oms_exchange', name, definition)
    for name, definition in {
        'fulfillment_round': 'INT NOT NULL DEFAULT 1', 'round_quantity': 'BIGINT NULL',
        'round_recipient': 'JSON NULL',
    }.items():
        column(db, 'oms_exchange', name, definition)
    for table in ['oms_order_line', 'oms_order_shipment']:
        column(db, table, 'fulfillment_round', 'INT NOT NULL DEFAULT 1')
    execute("""CREATE TABLE IF NOT EXISTS oms_exchange_round (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, exchange_id BIGINT NOT NULL,
      round_no INT NOT NULL, stock_reference VARCHAR(64) NOT NULL, quantity BIGINT NOT NULL,
      target_sku VARCHAR(128) NOT NULL, state VARCHAR(24) NOT NULL,
      recipient_json JSON NOT NULL, basis_json JSON NOT NULL, snapshot_json JSON NULL,
      reason VARCHAR(500) NOT NULL, evidence VARCHAR(500) NOT NULL, actor VARCHAR(64) NOT NULL,
      reviewed_by VARCHAR(64) NOT NULL DEFAULT '', review_reason VARCHAR(500) NOT NULL DEFAULT '',
      reviewed_at DATETIME NULL, fulfillment_line_id BIGINT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY round_scope(company_code,exchange_id,round_no), UNIQUE KEY stock_scope(company_code,stock_reference),
      KEY parent(company_code,exchange_id,state,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)
    if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_exchange' AND index_name='source_capacity' LIMIT 1", db).strip():
        execute('ALTER TABLE oms_exchange ADD KEY source_capacity(company_code,source_shipment_line_id,state,id)', db)
    column(db, 'oms_exchange_config', 'lease_token', 'VARCHAR(32) NULL')
    column(db, 'oms_exchange_config', 'lease_until', 'DATETIME NULL')
    for name, definition in {'fulfillment_mode': "VARCHAR(24) NOT NULL DEFAULT ''", 'return_logistics': 'JSON NULL', 'return_review': 'JSON NULL', 'linked_exchange_id': 'BIGINT NULL'}.items():
        column(db, 'oms_exchange', name, definition)
    column(db, 'oms_exchange', 'return_result', 'JSON NULL')
    column(db, 'oms_exchange', 'fulfillment_hold', 'TINYINT NOT NULL DEFAULT 0')
    column(db, 'oms_exchange', 'control_state', "VARCHAR(24) NOT NULL DEFAULT 'NONE'")
    execute("""CREATE TABLE IF NOT EXISTS oms_exchange_control (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, exchange_id BIGINT NOT NULL,
      kind VARCHAR(24) NOT NULL, document_id BIGINT NOT NULL DEFAULT 0, mode VARCHAR(24) NOT NULL,
      parent_id BIGINT NULL,
      state VARCHAR(24) NOT NULL DEFAULT 'PENDING', reason VARCHAR(500) NOT NULL, actor VARCHAR(64) NOT NULL,
      stock_request JSON NULL, evidence JSON NULL, last_error VARCHAR(500) NOT NULL DEFAULT '',
      request_version BIGINT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY intent(company_code,exchange_id,request_version,kind,document_id), KEY parent(company_code,exchange_id,state,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)
    column(db, 'oms_exchange', 'return_qc', "VARCHAR(16) NOT NULL DEFAULT 'PENDING'")
    column(db, 'oms_exchange', 'return_qc_reason', "VARCHAR(500) NOT NULL DEFAULT ''")
    if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_exchange' AND index_name='fulfillment_scope' LIMIT 1", db).strip():
        execute('ALTER TABLE oms_exchange ADD KEY fulfillment_scope(company_code,fulfillment_order_id,id)', db)
    for name, definition in {'source_kind': "VARCHAR(24) NOT NULL DEFAULT 'PLATFORM'", 'source_exchange_id': 'BIGINT NULL', 'source_platform_order_id': 'BIGINT NULL'}.items():
        column(db, 'oms_platform_order', name, definition)
    fields = execute("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_order' AND index_name='uk_business_platform'", db).strip()
    if fields and 'stock_reference' not in fields:
        execute('ALTER TABLE oms_order DROP INDEX uk_business_platform, ADD UNIQUE KEY uk_business_platform(company_code,channel_id,platform_tid,stock_reference)', db)


    from configure_platform_log_center import install_order_lookup
    install_order_lookup(db)


def install_channel(db):
    execute("""CREATE TABLE IF NOT EXISTS channel_exchange_access (
      company_code VARCHAR(64) NOT NULL, channel_id BIGINT NOT NULL,
      read_enabled TINYINT NOT NULL DEFAULT 0, write_enabled TINYINT NOT NULL DEFAULT 0,
      revision BIGINT NOT NULL DEFAULT 1, updated_by VARCHAR(64) NOT NULL,
      PRIMARY KEY(company_code,channel_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS channel_exchange_sim (
      company_code VARCHAR(64) NOT NULL, channel_id BIGINT NOT NULL, dispute_id VARCHAR(64) NOT NULL,
      payload JSON NOT NULL, modified DATETIME NOT NULL,
      PRIMARY KEY(company_code,channel_id,dispute_id), KEY sync_scope(company_code,channel_id,modified,dispute_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)


def install_supplychain(db):
    execute("""CREATE TABLE IF NOT EXISTS wms_exchange_return (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, exchange_id BIGINT NOT NULL,
      return_sn VARCHAR(64) NOT NULL, exchange_sn VARCHAR(64) NOT NULL, order_sn VARCHAR(64) NOT NULL,
      platform_tid VARCHAR(64) NOT NULL, original_shipment_sn VARCHAR(64) NOT NULL,
      store_code VARCHAR(64) NOT NULL, sku_sn VARCHAR(128) NOT NULL, quantity BIGINT NOT NULL,
      connection_id BIGINT NOT NULL, external_warehouse VARCHAR(100) NOT NULL, external_owner VARCHAR(100) NOT NULL,
      real_store_code VARCHAR(255) NOT NULL, owner_code VARCHAR(64) NOT NULL,
      payload_hash CHAR(64) NOT NULL, dispatch_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
      external_order VARCHAR(100) NOT NULL DEFAULT '', warehouse_status VARCHAR(64) NOT NULL DEFAULT '',
      receipt_state VARCHAR(24) NOT NULL DEFAULT 'WAITING', accepted_quantity BIGINT NOT NULL DEFAULT 0,
      posted_quantity BIGINT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0,
      lease_token VARCHAR(32) NULL, lease_until DATETIME NULL, last_error VARCHAR(500) NOT NULL DEFAULT '',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY exchange_scope(company_code,exchange_id), UNIQUE KEY sn_scope(company_code,return_sn),
      KEY platform_scope(company_code,platform_tid), KEY order_scope(company_code,order_sn),
      KEY warehouse_scope(company_code,external_order)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS wms_exchange_return_event (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, task_id BIGINT NOT NULL,
      message_id VARCHAR(128) NOT NULL, payload_hash CHAR(64) NOT NULL, payload JSON NOT NULL,
      quantity BIGINT NOT NULL, final_confirmation TINYINT NOT NULL, source_log_id BIGINT NULL,
      state VARCHAR(24) NOT NULL DEFAULT 'PENDING', last_error VARCHAR(500) NOT NULL DEFAULT '',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY message_scope(company_code,task_id,message_id), KEY parent(company_code,task_id,state)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS wms_exchange_return_line (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, task_id BIGINT NOT NULL,
      event_id BIGINT NOT NULL, line_no INT NOT NULL, sku_sn VARCHAR(128) NOT NULL,
      batch_code VARCHAR(100) NOT NULL, good_qty BIGINT NOT NULL, bad_qty BIGINT NOT NULL,
      posted TINYINT NOT NULL DEFAULT 0, UNIQUE KEY source_line(company_code,event_id,line_no),
      KEY due(company_code,task_id,posted)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)
    column(db, 'wms_exchange_return', 'unit_cost', 'DECIMAL(20,6) NOT NULL DEFAULT 0')
    column(db, 'wms_exchange_return', 'ticket_id', 'BIGINT NULL')
    if execute("SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='wms_tickets'", db).strip():
        column(db, 'wms_tickets', 'root_order_sn', "VARCHAR(64) NOT NULL DEFAULT ''")
        column(db, 'wms_tickets', 'exchange_id', 'BIGINT NULL')
        column(db, 'wms_tickets', 'cutoff_state', "VARCHAR(24) NOT NULL DEFAULT ''")
        column(db, 'wms_tickets', 'cancelled_quantity', 'BIGINT NOT NULL DEFAULT 0')
        column(db, 'wms_tickets_goods', 'cancelled_quantity', 'BIGINT NOT NULL DEFAULT 0')
    column(db, 'wms_exchange_return', 'sender_json', 'JSON NULL')
    for name, definition in {
        'generation': 'INT NOT NULL DEFAULT 1', 'request_quantity': 'BIGINT NOT NULL DEFAULT 0',
        'cancel_state': "VARCHAR(24) NOT NULL DEFAULT 'NONE'",
        'cancel_mode': "VARCHAR(24) NOT NULL DEFAULT ''", 'cancel_reason': "VARCHAR(500) NOT NULL DEFAULT ''",
        'cancel_actor': "VARCHAR(64) NOT NULL DEFAULT ''", 'cancelled_quantity': 'BIGINT NOT NULL DEFAULT 0',
        'control_version': 'BIGINT NOT NULL DEFAULT 1', 'cancelled_at': 'DATETIME NULL',
        'input_json': 'JSON NULL',
    }.items():
        column(db, 'wms_exchange_return', name, definition)
    fields = execute("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_exchange_return' AND index_name='exchange_scope'", db).strip()
    if fields and 'generation' not in fields:
        execute('ALTER TABLE wms_exchange_return DROP INDEX exchange_scope, ADD UNIQUE KEY exchange_scope(company_code,exchange_id,generation)', db)
    execute("""CREATE TABLE IF NOT EXISTS wms_exchange_return_review (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, task_id BIGINT NOT NULL,
      message_id VARCHAR(128) NOT NULL, payload_hash CHAR(64) NOT NULL, payload JSON NOT NULL,
      quantity BIGINT NOT NULL, source_log_id BIGINT NULL, state VARCHAR(24) NOT NULL DEFAULT 'WAIT_REVIEW',
      reason VARCHAR(500) NOT NULL DEFAULT '', actor VARCHAR(64) NOT NULL DEFAULT '',
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY receipt(company_code,task_id,message_id,payload_hash), KEY parent(company_code,task_id,state,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)
    if execute("SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='wms_outbound_task'", db).strip():
        column(db, 'wms_outbound_task', 'business_type', "VARCHAR(24) NOT NULL DEFAULT 'SALE'")
        column(db, 'wms_outbound_task', 'business_owner_sn', "VARCHAR(64) NOT NULL DEFAULT ''")
        column(db, 'wms_outbound_task', 'exchange_id', 'BIGINT NULL')
        if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_outbound_task' AND index_name='log_exchange_owner' LIMIT 1", db).strip():
            execute('ALTER TABLE wms_outbound_task ADD KEY log_exchange_owner(company_code,business_owner_sn,id)', db)
    if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_exchange_return' AND index_name='log_exchange_owner' LIMIT 1", db).strip():
        execute('ALTER TABLE wms_exchange_return ADD KEY log_exchange_owner(company_code,exchange_sn,id)', db)


def permissions():
    from seed_business import ensure_menu
    parent = int(execute("SELECT menu_id FROM sys_menu WHERE path='oms-order' AND parent_id=0 LIMIT 1").strip())
    menu = ensure_menu('换货管理', parent, 'exchanges', 'oms/order/exchanges', permission='order:exchange:list', order=4)
    for i, (name, permission) in enumerate([
        ('查看换货', 'order:exchange:query'), ('同步换货', 'order:exchange:sync'),
        ('审核换货', 'order:exchange:review'), ('执行换货', 'order:exchange:execute'),
        ('维护换货同步', 'order:exchange:config'), ('创建协商换品', 'order:exchange:create'),
    ]):
        child = ensure_menu(name, menu, '', kind='F', permission=permission, order=20+i)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')
    execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{menu})')
    if execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='exchangeTask.scanAll()'").strip() == '0':
        execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark) VALUES ('天猫换货申请同步与履约扫描','DEFAULT','exchangeTask.scanAll()','0/30 * * * * ?','3','1','0','admin',NOW(),'仅处理已启用店铺；同步自动建单不代表自动审核；平台结果不明先查询')")


if __name__ == '__main__':
    install_order('qm_oms_saas_order')
    install_channel('qm_oms_saas_channel')
    install_supplychain('qm_oms_saas_commodity')
    permissions()
    print('Exchange schema installed; existing documents and job switches unchanged')
