"""Install fulfillment cancellation metadata, without changing existing documents or job switches."""
from configure_order import column
from local_db import execute

def install_order(database):
    for name, definition in {
        'cutoff_state': "VARCHAR(24) NOT NULL DEFAULT ''",
        'cutoff_mode': "VARCHAR(16) NOT NULL DEFAULT ''",
        'cutoff_id': 'BIGINT NULL',
    }.items(): column(database, 'oms_order', name, definition)
    for table in ['oms_order_line', 'oms_order_shipment', 'oms_order_shipment_line']:
        column(database, table, 'cancelled_quantity', 'BIGINT NOT NULL DEFAULT 0')
    execute("""CREATE TABLE IF NOT EXISTS oms_order_cutoff (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL,
      order_id BIGINT NOT NULL, mode VARCHAR(16) NOT NULL, scope_all TINYINT NOT NULL,
      state VARCHAR(24) NOT NULL DEFAULT 'PENDING', reason VARCHAR(200) NOT NULL,
      operator_name VARCHAR(64) NOT NULL, last_error VARCHAR(500) NOT NULL DEFAULT '',
      next_attempt DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at DATETIME NULL,
      KEY due(company_code,state,next_attempt), KEY order_scope(company_code,order_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_order_cutoff_item (
      id BIGINT AUTO_INCREMENT PRIMARY KEY, company_code VARCHAR(50) NOT NULL,
      cutoff_id BIGINT NOT NULL, shipment_id BIGINT NOT NULL, shipment_sn VARCHAR(64) NOT NULL,
      state VARCHAR(24) NOT NULL DEFAULT 'PENDING', warehouse_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
      stock_request JSON NULL, stock_receipt JSON NULL, last_error VARCHAR(500) NOT NULL DEFAULT '',
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY item_scope(company_code,cutoff_id,shipment_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""", database)
    # A submitted retry must not be mistaken for an already displayed result.
    for name, definition in {
        'check_version': 'BIGINT NOT NULL DEFAULT 1',
        'checked_version': 'BIGINT NOT NULL DEFAULT 0',
        'last_checked_at': 'DATETIME NULL',
    }.items(): column(database, 'oms_order_cutoff', name, definition)

def install_inventory(database):
    for table, name in [('oms_order_stock_demand','cancelled_quantity'),('oms_order_stock_source','released_quantity'),('oms_order_warehouse_allocation','released_quantity')]:
        column(database,table,name,'BIGINT NOT NULL DEFAULT 0')
    execute("""CREATE TABLE IF NOT EXISTS oms_order_fulfillment_adjustment (
      company_code VARCHAR(50) NOT NULL, request_id VARCHAR(64) NOT NULL,
      payload_hash VARCHAR(64) NOT NULL, receipt_json JSON NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY(company_code,request_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",database)

def install_supply(database):
    for name,definition in {'cancel_state':"VARCHAR(24) NOT NULL DEFAULT 'NONE'",'cancel_reason':"VARCHAR(200) NOT NULL DEFAULT ''"}.items():
        column(database,'wms_outbound_task',name,definition)

def install_permissions():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='business' AND component='oms/order/index' ORDER BY menu_id LIMIT 1").strip()
    menu=ensure_menu('业务订单截单',int(parent),'',kind='F',permission='order:order:cutoff',order=39)
    execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{menu})')

if __name__=='__main__':
    install_order('qm_oms_saas_order');install_inventory('qm_oms_saas_inventory');install_supply('qm_oms_saas_commodity');install_permissions()
    print('Cutoff schema installed; no document changes or job switch changes')
