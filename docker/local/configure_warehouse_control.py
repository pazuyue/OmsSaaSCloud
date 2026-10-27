"""Install scoped warehouse reconciliation / transfer tables and admin menus; no historical rewrites."""
from pathlib import Path
import sys
from local_db import execute

def install(warehouse,inventory):
    here=Path(__file__).parent/'migrations'
    execute((here/'20261026_stock_movement.sql').read_text('utf-8'),inventory)
    execute((here/'20261026_warehouse_control.sql').read_text('utf-8'),warehouse)
    columns={'warehouse_transfer_execution':{'connection_id':"BIGINT NOT NULL DEFAULT 0 COMMENT '原仓库连接ID，用于配置引用索引'"},'warehouse_reconciliation':{'connection_id':"BIGINT NOT NULL DEFAULT 0 COMMENT '原仓库连接ID，用于配置引用索引'"},'warehouse_stock_adjustment':{'attempts':"INT NOT NULL DEFAULT 0 COMMENT '入账尝试次数'",'next_attempt_at':"DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '失败入账退避时间'",'lease_token':"VARCHAR(64) NULL COMMENT '调整入账租约'",'lease_until':"DATETIME NULL COMMENT '调整租约到期'"}}
    for table,fields in columns.items():
        for name,definition in fields.items():
            if not execute("SHOW COLUMNS FROM "+table+" LIKE '"+name+"'",warehouse).strip():execute('ALTER TABLE '+table+' ADD COLUMN '+name+' '+definition,warehouse)
    if not execute("SHOW COLUMNS FROM warehouse_reconciliation LIKE 'scope_hash'",warehouse).strip():
        execute("ALTER TABLE warehouse_reconciliation ADD COLUMN scope_hash CHAR(64) NOT NULL DEFAULT '' COMMENT '规范范围摘要，定位同范围最新成功快照'",warehouse)
        execute("UPDATE warehouse_reconciliation SET scope_hash=UPPER(SHA2(scope_json,256)) WHERE scope_hash=''",warehouse)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='warehouse_reconciliation' AND index_name='latest_scope'",warehouse).strip()=='0':execute("ALTER TABLE warehouse_reconciliation ADD KEY latest_scope(company_code,scope_hash,state,id) COMMENT '同范围最新成功快照，抑制过期差异待办'",warehouse)
    for table in ['warehouse_transfer_execution','warehouse_reconciliation']:
        if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='"+table+"' AND index_name='company_connection'",warehouse).strip()=='0':execute('ALTER TABLE '+table+" ADD KEY company_connection(company_code,connection_id,id) COMMENT '原连接索引，禁止全表搜索JSON引用'",warehouse)

    from configure_full_stock_check import install as full_install
    full_install(warehouse,inventory)


def menus():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-supplychain' LIMIT 1").strip();assert parent.isdigit()
    for path,title,kind,actions in [('warehouseTransfers','跨仓调拨','transfer',['query','create','approve','retry','stop','queryWarehouse','recover']),('warehouseReconciliation','库存对账','reconciliation',['query','create','adjust','review'])]:
        node=ensure_menu(title,int(parent),path,component='oms/purchase/WarehouseControl',permission='warehouse:'+kind+':query',order=5)
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{node})')
        for action in actions:
            child=ensure_menu(title+'-'+action,node,action,kind='F',permission='warehouse:'+kind+':'+action)
            execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')

if __name__=='__main__':
    assert sys.argv[1:] in (['--tests'],['--local']);test=sys.argv[1]=='--tests'
    install('purchase_workspace_test' if test else 'qm_oms_saas_commodity','order_inventory_test' if test else 'qm_oms_saas_inventory')
    if not test:menus()
    print('Warehouse control schema installed; existing stock, documents, jobs and queues preserved')
