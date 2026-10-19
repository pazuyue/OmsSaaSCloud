"""Warehouse assembly/disassembly schema. No document backfill."""
from local_db import execute
from configure_order import column
from configure_bundles import install_goods,install_inventory,install_order

def install_supplychain(db):
    install_goods(db)
    expression=execute("SELECT generation_expression FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wms_tickets' AND column_name='purchase_receipt_key'",db)
    if expression.strip() and 'business_type' not in expression:
        execute("ALTER TABLE wms_tickets MODIFY purchase_receipt_key VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN ticket_type=1 AND business_type IN ('','PURCHASE_IN') THEN NULLIF(relation_sn,'') ELSE NULL END) STORED",db)
    column(db,'goods_bundle','inventory_mode',"VARCHAR(16) NOT NULL DEFAULT 'VIRTUAL'")
    column(db,'goods_bundle_version','inventory_mode',"VARCHAR(16) NOT NULL DEFAULT 'VIRTUAL'")
    column(db,'wms_connection','storeprocess_enabled','TINYINT NOT NULL DEFAULT 0')
    column(db,'wms_connection','storeprocess_cancel','TINYINT NOT NULL DEFAULT 0')
    execute("""CREATE TABLE IF NOT EXISTS wms_process_order (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,process_sn VARCHAR(64) NOT NULL,
      request_key VARCHAR(64) NOT NULL,request_hash CHAR(64) NOT NULL,operation VARCHAR(16) NOT NULL,
      sku_sn VARCHAR(128) NOT NULL,goods_name VARCHAR(255) NOT NULL,revision BIGINT NOT NULL,
      recipe_json JSON NOT NULL,quantity BIGINT NOT NULL,completed_quantity BIGINT NOT NULL DEFAULT 0,
      store_code VARCHAR(64) NOT NULL,real_store_code VARCHAR(64) NOT NULL,owner_code VARCHAR(64) NOT NULL,
      connection_id BIGINT NOT NULL,external_warehouse VARCHAR(100) NOT NULL,external_owner VARCHAR(100) NOT NULL,
      external_order VARCHAR(100) NOT NULL DEFAULT '',state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
      dispatch_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',last_error VARCHAR(1000) NOT NULL DEFAULT '',
      control_version BIGINT NOT NULL DEFAULT 1,lease_token VARCHAR(64) NULL,lease_until DATETIME NULL,
      source_batch_id BIGINT NULL,stock_json JSON NULL,payload_xml MEDIUMTEXT NULL,reason VARCHAR(255) NOT NULL,
      created_by VARCHAR(64) NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY company_sn(company_code,process_sn),UNIQUE KEY company_request(company_code,request_key),
      KEY company_list(company_code,id),KEY external_lookup(company_code,external_order)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS wms_process_event (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,process_id BIGINT NOT NULL,
      message_id VARCHAR(100) NOT NULL,payload_hash CHAR(64) NOT NULL,payload_json JSON NOT NULL,
      quantity BIGINT NOT NULL,state VARCHAR(24) NOT NULL DEFAULT 'PENDING',last_error VARCHAR(1000) NOT NULL DEFAULT '',
      source_log_id BIGINT NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE KEY message_identity(company_code,process_id,message_id),KEY process_events(company_code,process_id,id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",db)

    column(db,'wms_process_event','reviewed','TINYINT NOT NULL DEFAULT 0')
    column(db,'wms_process_event','review_actor',"VARCHAR(64) NOT NULL DEFAULT ''")
    column(db,'wms_process_event','review_reason',"VARCHAR(255) NOT NULL DEFAULT ''")
    column(db,'wms_process_order','last_action',"VARCHAR(16) NOT NULL DEFAULT 'CREATE'")

def menu():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-inventory' LIMIT 1").strip()
    assert parent.isdigit(),'Inventory menu missing'
    key=ensure_menu('套装加工',int(parent),'kitProcess','oms/inventory/KitProcess',permission='warehouse:process:query',order=8)
    for name,permission in [('查询','warehouse:process:query'),('操作','warehouse:process:edit')]:
        child=ensure_menu(name,key,permission,kind='F',permission=permission)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')
    execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{key})')

def install_stock(db):
    install_inventory(db)
    execute("""CREATE TABLE IF NOT EXISTS oms_stock_process (
      company_code VARCHAR(50) NOT NULL,process_sn VARCHAR(64) NOT NULL,store_code VARCHAR(64) NOT NULL,
      operation VARCHAR(16) NOT NULL,sku_sn VARCHAR(128) NOT NULL,quantity BIGINT NOT NULL,
      completed_quantity BIGINT NOT NULL DEFAULT 0,state VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
      recipe_json JSON NOT NULL,request_hash CHAR(64) NOT NULL,
      PRIMARY KEY(company_code,process_sn)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_stock_process_hold (
      company_code VARCHAR(50) NOT NULL,process_sn VARCHAR(64) NOT NULL,batch_id BIGINT NOT NULL,
      sku_sn VARCHAR(128) NOT NULL,quantity BIGINT NOT NULL,consumed_quantity BIGINT NOT NULL DEFAULT 0,
      released_quantity BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(company_code,process_sn,batch_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_stock_process_event (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,company_code VARCHAR(50) NOT NULL,process_sn VARCHAR(64) NOT NULL,
      message_id VARCHAR(100) NOT NULL,payload_hash CHAR(64) NOT NULL,result_json JSON NOT NULL,
      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY event_key(company_code,process_sn,message_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    CREATE TABLE IF NOT EXISTS oms_stock_process_origin (
      company_code VARCHAR(50) NOT NULL,batch_id BIGINT NOT NULL,process_sn VARCHAR(64) NOT NULL,
      recipe_json JSON NOT NULL,cost_json JSON NOT NULL,PRIMARY KEY(company_code,batch_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",db)

if __name__=='__main__':
    install_supplychain('qm_oms_saas_commodity');install_stock('qm_oms_saas_inventory')
    install_order('qm_oms_saas_order');column('qm_oms_saas_order','oms_order_line','stocked_bundle_snapshot','JSON NULL')
    menu()
    print('Installed warehouse process schema and menu')
