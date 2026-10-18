"""Purchase receipt cutoff schema. Does not backfill documents or change scheduler settings."""
from local_db import execute


def install(database):
    from configure_order import column
    for table in ['po_info', 'no_tickets']:
        column(database, table, 'cutoff_state', "VARCHAR(24) NOT NULL DEFAULT ''")
        column(database, table, 'cutoff_id', 'BIGINT NULL')
        column(database, table, 'cutoff_version', 'BIGINT NOT NULL DEFAULT 0')
    for table in ['no_tickets', 'no_tickets_goods', 'wms_tickets', 'wms_tickets_goods']:
        column(database, table, 'cancelled_quantity', 'BIGINT NOT NULL DEFAULT 0')
    column(database, 'wms_tickets', 'cutoff_state', "VARCHAR(24) NOT NULL DEFAULT ''")
    column(database, 'wms_inbound_task', 'cancel_state', "VARCHAR(24) NOT NULL DEFAULT 'NONE'")
    column(database, 'wms_connection', 'partial_inbound_cancel', 'TINYINT NOT NULL DEFAULT 0')
    execute('''CREATE TABLE IF NOT EXISTS purchase_cutoff (
      id BIGINT PRIMARY KEY AUTO_INCREMENT,company_code VARCHAR(64) NOT NULL,purchase_id BIGINT NOT NULL,
      scope VARCHAR(16) NOT NULL,receipt_id BIGINT NULL,state VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
      reason VARCHAR(500) NOT NULL,operator_name VARCHAR(100) NOT NULL,last_error VARCHAR(1000) NOT NULL DEFAULT '',
      next_attempt DATETIME NULL DEFAULT CURRENT_TIMESTAMP,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,completed_at DATETIME NULL,
      KEY cutoff_purchase(company_code,purchase_id,id),KEY cutoff_due(company_code,next_attempt)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS purchase_cutoff_item (
      id BIGINT PRIMARY KEY AUTO_INCREMENT,company_code VARCHAR(64) NOT NULL,cutoff_id BIGINT NOT NULL,
      receipt_id BIGINT NOT NULL,receipt_sn VARCHAR(100) NOT NULL,ticket_id BIGINT NULL,ticket_sn VARCHAR(100) NOT NULL DEFAULT '',
      state VARCHAR(24) NOT NULL DEFAULT 'PENDING',warehouse_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
      cancelled_quantity BIGINT NOT NULL DEFAULT 0,confirmed_received BIGINT NULL,
      manual_evidence VARCHAR(500) NOT NULL DEFAULT '',last_error VARCHAR(1000) NOT NULL DEFAULT '',
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY cutoff_receipt(company_code,cutoff_id,receipt_id),KEY receipt_cutoff(company_code,receipt_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS wms_inbound_receipt_review (
      id BIGINT PRIMARY KEY AUTO_INCREMENT,company_code VARCHAR(64) NOT NULL,task_id BIGINT NOT NULL,ticket_id BIGINT NOT NULL,
      ticket_sn VARCHAR(100) NOT NULL,message_id VARCHAR(128) NOT NULL,receipt_hash VARCHAR(64) NOT NULL,receipt_json MEDIUMTEXT NOT NULL,
      source_log_id BIGINT NOT NULL DEFAULT 0,state VARCHAR(24) NOT NULL DEFAULT 'WAIT_REVIEW',
      conflict_message VARCHAR(500) NOT NULL,reason VARCHAR(500) NOT NULL DEFAULT '',operator_name VARCHAR(100) NOT NULL DEFAULT '',
      control_version BIGINT NOT NULL DEFAULT 0,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,decided_at DATETIME NULL,
      UNIQUE KEY inbound_review(company_code,task_id,receipt_hash),KEY inbound_review_ticket(company_code,ticket_id,state,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;''', database)

    for name,fields in [('arrival_log','company_key,relation_sn,id'),('purchase_log','company_key,original_sn,id')]:
        if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_tickets' AND index_name='"+name+"'",database).strip()=='0':
            execute('ALTER TABLE wms_tickets ADD INDEX '+name+'('+fields+')',database)


def permissions():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE component='oms/poInfo/index' LIMIT 1").strip()
    assert parent.isdigit(), 'Purchase menu missing'
    for i,(name,permission) in enumerate([('到货单截单与采购终止','warehouse:poInfo:cutoff'),('人工核对采购入库截单','warehouse:poInfo:cutoffReview')],50):
        ident=ensure_menu(name,int(parent),'',kind='F',permission=permission,order=i)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{ident})')


if __name__=='__main__':
    install('qm_oms_saas_commodity');permissions()
    print('Purchase cutoff schema and permissions installed; no document or stock changes.')
