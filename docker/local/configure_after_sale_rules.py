"""Install independent after-sales return rules navigation; preserve all business policies."""
from local_db import execute
from seed_business import ensure_menu


def menus():
    order = execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-order' LIMIT 1").strip()
    assert order.isdigit(), 'Order navigation is missing'
    group = ensure_menu('售后规则', int(order), 'afterSaleRules', 'ParentView', kind='M', order=16)
    page = ensure_menu('退货规则', group, 'returns', 'oms/order/RefundReturnRules', permission='order:refund:config', order=1)
    execute(f"UPDATE sys_menu SET menu_name='售后规则',component='ParentView',icon='setting',visible='0',status='0' WHERE menu_id={group}")
    execute(f"UPDATE sys_menu SET menu_name='退货规则',icon='setting',visible='0',status='0' WHERE menu_id={page}")
    # Move navigation access with the existing configuration permission, without granting new operations.
    for menu in [int(order), group, page]:
        execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,{menu} FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms='order:refund:config'")
        execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{menu})')
    print('After-sales rules -> Return rules installed; existing shop policies and documents preserved')


if __name__ == '__main__':
    menus()
