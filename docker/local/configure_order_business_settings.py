"""Consolidate navigation only. Preserve configuration, permission nodes, tasks and stock."""
from local_db import execute
from seed_business import ensure_menu


def install():
    parent = execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-order' LIMIT 1").strip()
    assert parent.isdigit(), 'Order navigation missing'
    settings = ensure_menu('业务设置', int(parent), 'businessSettings', 'oms/order/OrderBusinessSettings', order=6)
    tasks = ensure_menu('同步任务', int(parent), 'syncTasks', 'oms/order/OrderSyncTasks', order=5)
    permissions = {
        settings: "'order:pull:config','order:source:config','order:refund:config','order:exchange:config','order:shipment:platformConfig','order:gift:list','channel:platform:query'",
        tasks: "'order:order:list','order:pull:execute','order:refund:sync','order:exchange:sync'",
    }
    for menu, perms in permissions.items():
        execute(f"UPDATE sys_menu SET visible='0',status='0',perms='',icon='{'setting' if menu==settings else 'download'}',is_cache=1,remark='订单业务统一入口；按原操作权限展示分区，不新增业务授权，不修改店铺配置' WHERE menu_id={menu}")
        for node in [int(parent), menu]:
            execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT rm.role_id,{node} FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.perms IN ({perms})")
            execute(f"INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{node})")
    # Retain these hidden routes and their existing permission nodes; direct links redirect to the center.
    execute(f"UPDATE sys_menu SET visible='1',remark='业务设置/同步任务的隐藏跳转入口；保留原权限节点及角色授权' WHERE parent_id={parent} AND path IN ('management','settings','refundSettings','afterSaleRules')")
    execute(f"UPDATE sys_menu SET visible='1' WHERE parent_id IN (SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE parent_id={parent} AND path='afterSaleRules') old_group)")
    print('Business settings and sync tasks navigation installed; policies, source switches and inventory unchanged')


if __name__ == '__main__':
    install()
