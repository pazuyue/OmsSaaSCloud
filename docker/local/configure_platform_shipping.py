"""Install notification metadata only. Shops remain OFF until explicitly configured."""
from local_db import execute, HERE


def install(database):
    execute((HERE / 'migrations/20261013_platform_shipping.sql').read_text(encoding='utf8'), database)


def permissions():
    from seed_business import ensure_menu
    parent = execute("SELECT menu_id FROM sys_menu WHERE component='oms/order/shipments' ORDER BY menu_id LIMIT 1").strip()
    if not parent:
        raise RuntimeError('Shipment menu missing')
    for title, perm in [('平台发货回传', 'order:shipment:platformShipping'), ('平台回传配置', 'order:shipment:platformConfig')]:
        menu = ensure_menu(title, int(parent), '', kind='F', permission=perm, order=45)
        execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')


if __name__ == '__main__':
    install('qm_oms_saas_order')
    permissions()
    print('Platform shipping metadata installed; all shops remain OFF')
