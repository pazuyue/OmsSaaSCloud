"""Virtual outbound schema and permission only; never post any live shipment."""
from local_db import HERE, execute


def install_order(database):
    execute((HERE/'migrations/20261012_order_virtual_outbound.sql').read_text(encoding='utf-8'), database)


def install_inventory(database):
    from configure_order import column
    execute((HERE/'migrations/20261012_inventory_virtual_outbound.sql').read_text(encoding='utf-8'), database)
    for table in ['oms_order_stock_demand', 'oms_order_stock_source', 'oms_order_warehouse_allocation']:
        column(database, table, 'outbound_quantity', 'BIGINT NOT NULL DEFAULT 0')
    column(database, 'oms_order_warehouse_allocation', 'outbound_shipment_sn', "VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT ''")

    from configure_order_receipts import install_inventory as install_receipts
    install_receipts(database)


def install_permissions():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='shipments' AND component='oms/order/shipments' ORDER BY menu_id LIMIT 1").strip()
    if not parent.isdigit(): raise RuntimeError('Shipment menu missing')
    menu=ensure_menu('发货单虚拟出库', int(parent), '', kind='F', permission='order:shipment:virtualOutbound', order=4)
    execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')


if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_inventory('qm_oms_saas_inventory')
    install_permissions()
    print('Virtual outbound schema and permission installed; no document or stock changes.')
