"""Idempotent warehouse-allocation schema/permission installation; no stock/data repair."""
from local_db import HERE, execute
from configure_order import column

def install_order(database):
    from configure_order_shipments import install_order as install_shipments
    install_shipments(database)
    sql=(HERE/'migrations/20261011_order_warehouse.sql').read_text(encoding='utf-8')
    execute(sql.split('ALTER TABLE oms_order')[0],database)
    for name,definition in {
        'allocation_status': "VARCHAR(24) NOT NULL DEFAULT 'NONE'",
        'allocation_quantity': 'BIGINT NOT NULL DEFAULT 0',
        'allocation_policy': 'TEXT NULL',
        'allocation_receipts': 'MEDIUMTEXT NULL',
        'allocation_error': "VARCHAR(500) NOT NULL DEFAULT ''",
        'allocation_at': 'DATETIME NULL',
    }.items():column(database,'oms_order',name,definition)

def install_inventory(database):
    execute((HERE/'migrations/20261011_order_warehouse_inventory.sql').read_text(encoding='utf-8'),database)
    for name,fields in [('demand_scope','company_code,demand_id,status'),('batch_scope','company_code,batch_id,source_type,status')]:
        if execute(f"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_order_warehouse_allocation' AND index_name='{name}'",database).strip()=='0':
            execute(f'ALTER TABLE oms_order_warehouse_allocation ADD INDEX {name}({fields})',database)

def install_permissions():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='business' AND component='oms/order/index' ORDER BY menu_id LIMIT 1").strip()
    if not parent.isdigit():raise RuntimeError('Business order menu missing')
    menu=ensure_menu('业务订单分仓',int(parent),'',kind='F',permission='order:order:allocate',order=33)
    execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')
    from configure_order_settings import install
    install()
    from configure_order_shipments import install_permissions as install_shipments_permissions
    install_shipments_permissions()

if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_inventory('qm_oms_saas_inventory')
    install_permissions()
    print('Warehouse allocation schema and permission installed; shop rules remain explicitly configurable')
