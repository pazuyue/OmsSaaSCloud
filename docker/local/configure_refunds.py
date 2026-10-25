"""Install the two-layer refund workflow. No historical backfill or source writes."""
from local_db import execute
from configure_order import column


def install_order(db):
    execute("""
    CREATE TABLE IF NOT EXISTS oms_refund_config (
      company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL, source_key VARCHAR(64) NOT NULL,
      seller_nick VARCHAR(200) NOT NULL, enabled TINYINT NOT NULL DEFAULT 0, auto_convert TINYINT NOT NULL DEFAULT 0,
      auto_cutoff TINYINT NOT NULL DEFAULT 0, initial_time DATETIME NOT NULL, cursor_time DATETIME NOT NULL,
      revision BIGINT NOT NULL DEFAULT 1, interval_seconds INT NOT NULL DEFAULT 30,
      lease_token VARCHAR(32) NULL, lease_until DATETIME NULL, next_run DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      last_error VARCHAR(500) NOT NULL DEFAULT '', last_success DATETIME NULL,
      updated_by VARCHAR(64) NOT NULL, PRIMARY KEY(company_code,channel_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund_sync (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
      mode VARCHAR(16) NOT NULL, start_time DATETIME NOT NULL, end_time DATETIME NOT NULL, page_time DATETIME NOT NULL,
      page_refund_id VARCHAR(64) NOT NULL DEFAULT '0', selected_ids JSON NULL, request_key VARCHAR(64) NOT NULL,
      state VARCHAR(24) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, synced_count BIGINT NOT NULL DEFAULT 0,
      last_error VARCHAR(500) NOT NULL DEFAULT '', actor VARCHAR(64) NOT NULL,
      next_attempt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY request_scope(company_code,channel_id,request_key), KEY due(company_code,channel_id,state,next_attempt,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund_sync_item (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, task_id BIGINT NOT NULL,
      refund_id VARCHAR(64) NOT NULL, result VARCHAR(24) NOT NULL DEFAULT 'PENDING',
      platform_refund_id BIGINT NULL, message VARCHAR(500) NOT NULL DEFAULT '',
      UNIQUE KEY task_scope(company_code,task_id,refund_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_platform_refund (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL,
      refund_id VARCHAR(64) NOT NULL, platform_tid VARCHAR(64) NOT NULL DEFAULT '', platform_oid VARCHAR(64) NOT NULL DEFAULT '',
      seller_nick VARCHAR(200) NOT NULL, platform_status VARCHAR(64) NOT NULL DEFAULT '', dispute_type VARCHAR(32) NOT NULL DEFAULT '',
      source_phase VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN', special_type VARCHAR(64) NOT NULL DEFAULT '',
      refund_fee DECIMAL(20,2) NOT NULL DEFAULT 0, platform_quantity BIGINT NOT NULL DEFAULT 0,
      reason VARCHAR(500) NOT NULL DEFAULT '', source_modified DATETIME NULL, refund_version VARCHAR(64) NOT NULL DEFAULT '',
      jdp_modified DATETIME NOT NULL, source_hash CHAR(64) NOT NULL, payload MEDIUMTEXT NOT NULL,
      source_state VARCHAR(24) NOT NULL DEFAULT 'VALID', source_error VARCHAR(500) NOT NULL DEFAULT '',
      conversion_state VARCHAR(24) NOT NULL DEFAULT 'WAITING', conversion_error VARCHAR(500) NOT NULL DEFAULT '',
      business_refund_id BIGINT NULL, version BIGINT NOT NULL DEFAULT 1, last_sync DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY identity_scope(company_code,channel_id,refund_id), KEY platform_number(company_code,refund_id,id),
      KEY original_scope(company_code,channel_id,platform_tid,platform_oid,id), KEY convert_due(company_code,conversion_state,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_platform_refund_snapshot (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, platform_refund_id BIGINT NOT NULL,
      payload_hash CHAR(64) NOT NULL, payload MEDIUMTEXT NOT NULL, source_modified DATETIME NULL,
      jdp_modified DATETIME NOT NULL, outcome VARCHAR(24) NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY observed(company_code,platform_refund_id,payload_hash), KEY parent(company_code,platform_refund_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, refund_sn VARCHAR(64) NOT NULL,
      platform_refund_id BIGINT NOT NULL, channel_id BIGINT NOT NULL, refund_id VARCHAR(64) NOT NULL,
      order_id BIGINT NOT NULL, order_sn VARCHAR(64) NOT NULL, original_platform_order_id BIGINT NOT NULL,
      platform_tid VARCHAR(64) NOT NULL, platform_oid VARCHAR(64) NOT NULL,
      state VARCHAR(24) NOT NULL DEFAULT 'NEEDS_REVIEW', review_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
      purpose VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN', goods_action VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
      goods_state VARCHAR(24) NOT NULL DEFAULT 'WAIT_REVIEW', funds_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
      approved_version BIGINT NULL, approved_hash CHAR(64) NULL, approved_snapshot JSON NULL,
      refund_fee DECIMAL(20,2) NOT NULL, cancelled_quantity BIGINT NOT NULL DEFAULT 0,
      cancel_line_id BIGINT NULL, cancel_quantity BIGINT NOT NULL DEFAULT 0, cancel_command JSON NULL,
      cutoff_id BIGINT NULL, review_reason VARCHAR(500) NOT NULL DEFAULT '', review_evidence VARCHAR(1000) NOT NULL DEFAULT '',
      review_by VARCHAR(64) NOT NULL DEFAULT '', reviewed_at DATETIME NULL,
      problem_code VARCHAR(64) NOT NULL DEFAULT '', problem_message VARCHAR(500) NOT NULL DEFAULT '',
      control_version BIGINT NOT NULL DEFAULT 1, next_attempt DATETIME NULL,
      lease_token VARCHAR(32) NULL, lease_until DATETIME NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY sn_scope(company_code,refund_sn), UNIQUE KEY source_scope(company_code,platform_refund_id),
      KEY work_scope(company_code,state,id), KEY original_scope(company_code,order_id,platform_oid,id),
      KEY platform_number(company_code,refund_id,id), KEY due(company_code,next_attempt,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund_event (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, refund_id BIGINT NOT NULL,
      action VARCHAR(40) NOT NULL, message VARCHAR(1000) NOT NULL, actor VARCHAR(64) NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY parent(company_code,refund_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund_operation (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, refund_id BIGINT NOT NULL,
      request_id VARCHAR(64) NOT NULL, action VARCHAR(24) NOT NULL, state VARCHAR(24) NOT NULL,
      source_version BIGINT NOT NULL, platform_version VARCHAR(64) NOT NULL, fee DECIMAL(20,2) NOT NULL,
      reason VARCHAR(500) NOT NULL, actor VARCHAR(64) NOT NULL, error_code VARCHAR(128) NOT NULL DEFAULT '',
      result_message VARCHAR(500) NOT NULL DEFAULT '', challenge_until DATETIME NULL,
      next_attempt DATETIME NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY request_scope(company_code,request_id), KEY parent(company_code,refund_id,id), KEY due(company_code,state,next_attempt,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_refund_return_plan (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL, refund_id BIGINT NOT NULL,
      shipment_line_id BIGINT NOT NULL, shipment_id BIGINT NOT NULL, shipment_sn VARCHAR(64) NOT NULL,
      sku_sn VARCHAR(128) NOT NULL, quantity BIGINT NOT NULL, store_code VARCHAR(64) NOT NULL,
      return_address VARCHAR(500) NOT NULL, return_contact VARCHAR(200) NOT NULL, package_ids JSON NOT NULL,
      return_mode VARCHAR(24) NOT NULL, transport_state VARCHAR(24) NOT NULL DEFAULT 'WAITING',
      transport_evidence VARCHAR(1000) NOT NULL DEFAULT '', return_waybill VARCHAR(128) NOT NULL DEFAULT '',
      state VARCHAR(24) NOT NULL DEFAULT 'PLANNED', request_json JSON NULL, result_json JSON NULL,
      accepted_quantity BIGINT NOT NULL DEFAULT 0, posted_quantity BIGINT NOT NULL DEFAULT 0,
      qc_state VARCHAR(24) NOT NULL DEFAULT 'PENDING', qc_reason VARCHAR(500) NOT NULL DEFAULT '',
      last_error VARCHAR(500) NOT NULL DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY source_scope(company_code,refund_id,shipment_line_id), KEY claim_scope(company_code,shipment_line_id,state),
      KEY parent(company_code,refund_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """, db)
    for table in ['oms_platform_order', 'oms_order']:
        column(db, table, 'refund_block_reason', "VARCHAR(500) NOT NULL DEFAULT ''")
    column(db, 'oms_platform_refund', 'semantic_hash', "CHAR(64) NOT NULL DEFAULT ''")
    column(db, 'oms_refund_sync', 'source_key', "VARCHAR(64) NOT NULL DEFAULT ''")
    column(db, 'oms_refund_sync', 'seller_nick', "VARCHAR(200) NOT NULL DEFAULT ''")
    from configure_refund_execution import install_order as execution
    execution(db)
    from pathlib import Path
    execute((Path(__file__).parent/'migrations/20261024_refund_money_authorization.sql').read_text(encoding='utf-8'),db)
    from configure_order_intake import install as intake
    intake(db)
    from configure_refund_goods import install_order as goods
    goods(db)


    from configure_bundles import install_order as bundles
    bundles(db)

def install_channel(db):
    execute("""CREATE TABLE IF NOT EXISTS channel_refund_access (
      company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL, read_enabled TINYINT NOT NULL DEFAULT 0,
      write_enabled TINYINT NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 1,
      review_session_cipher TEXT NULL, payment_session_cipher TEXT NULL,
      updated_by VARCHAR(64) NOT NULL, PRIMARY KEY(company_code,channel_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS channel_refund_sim (
      company_code VARCHAR(50) NOT NULL, channel_id BIGINT NOT NULL, refund_id VARCHAR(64) NOT NULL,
      payload JSON NOT NULL, modified DATETIME NOT NULL,
      PRIMARY KEY(company_code,channel_id,refund_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", db)


def install_supplychain(db):
    for suffix in ['', '_event', '_line', '_review']:
        execute(f'CREATE TABLE IF NOT EXISTS wms_refund_return{suffix} LIKE wms_exchange_return{suffix}', db)
    names = execute("SHOW COLUMNS FROM wms_refund_return LIKE 'exchange_id'", db).strip()
    if names:
        execute('ALTER TABLE wms_refund_return CHANGE exchange_id plan_id BIGINT NOT NULL, CHANGE exchange_sn refund_sn VARCHAR(64) NOT NULL', db)
    column(db, 'wms_refund_return', 'refund_id', 'BIGINT NOT NULL DEFAULT 0')
    column(db, 'wms_tickets', 'refund_id', 'BIGINT NULL')


def permissions():
    from seed_business import ensure_menu
    parent = execute("SELECT parent_id FROM sys_menu WHERE path='business' AND component='oms/order/index' LIMIT 1").strip()
    assert parent.isdigit(), 'Order menu missing'
    ids = []
    for title, path, component, order in [('平台退款', 'platformRefunds', 'oms/order/PlatformRefunds', 7), ('业务退款', 'businessRefunds', 'oms/order/BusinessRefunds', 8), ('退款接入设置', 'refundSettings', 'oms/order/RefundSettings', 12)]:
        ident = ensure_menu(title, int(parent), path, component, permission='order:refund:list' if path != 'refundSettings' else 'order:refund:config', order=order)
        ids.append(ident)
    for index, (key, label) in enumerate([('query', '查看退款详情'), ('sync', '同步平台退款'), ('convert', '转业务退款单'), ('review', '审核退款方案'), ('execute', '处理退款事项')], 1):
        ids.append(ensure_menu(label, ids[1], '', kind='F', permission='order:refund:' + key, order=index))
    for ident in ids:
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{ident})')
    if execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='refundTask.scanAll()'").strip() == '0':
        execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark) VALUES ('天猫退款推送库同步与转单扫描','DEFAULT','refundTask.scanAll()','0/10 * * * * ?','3','1','0','admin',NOW(),'按公司店铺读取jdp_tb_refund；平台退款先落库再转业务；退款资金操作人工发起')")


if __name__ == '__main__':
    install_order('qm_oms_saas_order')
    permissions()
    print('Refund schema, menus and job installed; no source/business history modified')
