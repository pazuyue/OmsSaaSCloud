"""Install carrier matching schema/permissions without choosing carriers for real orders."""
from local_db import HERE, execute
from configure_order import column


def install_order(database):
    execute((HERE/'migrations/20261012_order_carriers.sql').read_text(encoding='utf-8'), database)
    for name, definition in {
        'receiver_province': "VARCHAR(32) NOT NULL DEFAULT ''",
        'carrier_code': "VARCHAR(64) NOT NULL DEFAULT ''",
        'carrier_name': "VARCHAR(100) NOT NULL DEFAULT ''",
        'warehouse_carrier_code': "VARCHAR(64) NOT NULL DEFAULT ''",
        'carrier_match_status': "VARCHAR(16) NOT NULL DEFAULT 'WAITING'",
        'carrier_match_mode': "VARCHAR(16) NOT NULL DEFAULT ''",
        'carrier_match_reason': "VARCHAR(500) NOT NULL DEFAULT ''",
        'carrier_attempt_at': 'DATETIME NULL',
        'carrier_matched_at': 'DATETIME NULL',
        'carrier_matched_by': "VARCHAR(100) NOT NULL DEFAULT ''",
        'carrier_snapshot': 'TEXT NULL',
        'control_version': 'BIGINT NOT NULL DEFAULT 0',
    }.items():
        column(database, 'oms_order_shipment', name, definition)


def install_permissions():
    from seed_business import ensure_menu
    parent = execute("SELECT menu_id FROM sys_menu WHERE path='shipments' AND component='oms/order/shipments' ORDER BY menu_id LIMIT 1").strip()
    if not parent.isdigit(): raise RuntimeError('Shipment menu missing')
    menu = ensure_menu('匹配与调整发货单快递', int(parent), '', kind='F', permission='order:shipment:carrier', order=1)
    execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')


if __name__ == '__main__':
    install_order('qm_oms_saas_order')
    install_permissions()
    print('Carrier schema/permission installed; no carrier selection or stock mutation.')
