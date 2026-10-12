"""Separate business settings from pull management, preserving menu IDs and policies."""
from local_db import execute
from seed_business import ensure_menu


def install():
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='oms-order' AND parent_id=0 ORDER BY menu_id LIMIT 1").strip()
    if not parent.isdigit():raise RuntimeError('Order management menu missing')
    menu=ensure_menu('订单设置',int(parent),'settings','oms/order/settings',permission='order:pull:config',order=6)
    execute(f"UPDATE sys_menu SET menu_name='订单设置',component='oms/order/settings',perms='order:pull:config',order_num=6,icon='tool',query='' WHERE menu_id={menu}")
    execute(f'''INSERT INTO sys_role_menu(role_id,menu_id)
      SELECT roles.role_id,{menu} FROM (
        SELECT 1 role_id UNION SELECT DISTINCT rm.role_id FROM sys_role_menu rm
        JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.perms='order:pull:config'
      ) roles WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=roles.role_id AND existing.menu_id={menu})''')
    return menu


if __name__=='__main__':
    install()
    print('Order settings menu installed; existing configuration and pull progress preserved')
