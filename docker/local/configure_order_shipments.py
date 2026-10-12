"""Idempotent shipment installation; never backfills business documents or stock."""
from local_db import HERE, execute
from seed_business import ensure_menu


def install_order(database):
    execute((HERE/'migrations/20261011_order_shipments.sql').read_text(encoding='utf-8'),database)
    from configure_order_carriers import install_order as install_carriers
    install_carriers(database)
    from configure_order_dispatch import install_order as install_dispatch
    install_dispatch(database)
    from configure_order_virtual_outbound import install_order as install_virtual
    install_virtual(database)
    from configure_order_receipts import install_order as install_receipts
    install_receipts(database)


def install_permissions():
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='oms-order' AND parent_id=0 ORDER BY menu_id LIMIT 1").strip()
    if not parent.isdigit():raise RuntimeError('Order menu missing')
    menu=ensure_menu('发货单',int(parent),'shipments','oms/order/shipments',permission='order:order:list',order=3)
    execute(f"UPDATE sys_menu SET menu_name='发货单',component='oms/order/shipments',perms='order:order:list',icon='list',query='' WHERE menu_id={menu}")
    for index,path in enumerate(['platform','business','shipments','exceptions','management','settings'],1):
        execute(f"UPDATE sys_menu SET order_num={index} WHERE parent_id={parent} AND path='{path}' AND menu_type='C'")
    execute(f'''INSERT INTO sys_role_menu(role_id,menu_id)
      SELECT r.role_id,{menu} FROM (SELECT 1 role_id UNION SELECT DISTINCT rm.role_id FROM sys_role_menu rm
      JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.parent_id={parent} AND m.perms='order:order:list') r
      WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu e WHERE e.role_id=r.role_id AND e.menu_id={menu})''')
    from configure_order_carriers import install_permissions as install_carriers_permissions
    install_carriers_permissions()
    from configure_order_dispatch import install_permissions as install_dispatch_permissions
    install_dispatch_permissions()
    from configure_order_virtual_outbound import install_permissions as install_virtual_permissions
    install_virtual_permissions()
    from configure_order_receipts import install_permissions as install_receipt_permissions
    install_receipt_permissions()


if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_permissions()
    print('Shipment tables and menu installed; no business or inventory backfill')
