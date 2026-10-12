"""Install shipment dispatch schema and permissions; never submit existing documents."""
from local_db import HERE, execute


def install_order(database):
    execute((HERE/'migrations/20261012_order_dispatch.sql').read_text(encoding='utf-8'), database)


def install_supplychain(database):
    execute((HERE/'migrations/20261012_wms_outbound.sql').read_text(encoding='utf-8'), database)
    from configure_order_receipts import install_supplychain as install_receipts
    install_receipts(database)


def install_permissions():
    from seed_business import ensure_menu
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='shipments' AND component='oms/order/shipments' ORDER BY menu_id LIMIT 1").strip()
    if not parent.isdigit(): raise RuntimeError('Shipment menu missing')
    for index,(title,permission) in enumerate([('下发与重试发货单','order:shipment:dispatch'),('查询发货单仓库结果','order:shipment:queryWarehouse')],2):
        menu=ensure_menu(title,int(parent),'',kind='F',permission=permission,order=index)
        execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')


if __name__=='__main__':
    install_order('qm_oms_saas_order')
    install_supplychain('qm_oms_saas_commodity')
    install_permissions()
    print('Dispatch schema and permissions installed; no warehouse configuration or document submission.')
