"""Install the independent oms-order service, its schema, gateway route and Quartz trigger.
Push connections are company-scoped database configuration. Never writes to the push database.
"""
import copy
import json
import urllib.parse
import urllib.request
from local_db import HERE, execute, quote
from seed_business import ensure_menu

ORDER_DB = 'qm_oms_saas_order'
INVENTORY_DB = 'qm_oms_saas_inventory'

def column(database, table, name, definition):
    found = execute(f"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name={quote(table)} AND column_name={quote(name)}", database).strip()
    if found == '0':
        execute(f'ALTER TABLE {table} ADD COLUMN {name} {definition}', database)
        return True
    return False

def install_inventory(database):
    execute((HERE/"migrations/20261014_allocation_stock_signal.sql").read_text(encoding="utf-8"),database)
    from configure_order_warehouse import install_inventory as install_warehouse_inventory
    install_warehouse_inventory(database)
    execute((HERE / 'migrations/20261010_order_stock.sql').read_text(encoding='utf-8'), database)
    execute((HERE / 'migrations/20261011_order_logical_stock.sql').read_text(encoding='utf-8'), database)
    from configure_business_orders import install_inventory as install_business_inventory
    install_business_inventory(database)
    from configure_order_virtual_outbound import install_inventory as install_virtual_outbound
    install_virtual_outbound(database)
    from configure_bundles import install_inventory as bundles
    bundles(database)
    column(database, 'oms_inventory', 'pending_public_stock', "BIGINT NOT NULL DEFAULT 0 COMMENT '已销售但尚未逻辑预占的公共库存需求，不属于实物库存'")
    added = column(database, 'oms_channel_inventory', 'quota_stock', "BIGINT NOT NULL DEFAULT 0 COMMENT '普通分货额度基数，与最终可售分开维护'")
    column(database, 'oms_channel_inventory', 'quota_reserved_stock', "BIGINT NOT NULL DEFAULT 0 COMMENT '已逻辑预占中使用普通额度的数量'")
    column(database, 'oms_channel_inventory', 'pending_stock', "BIGINT NOT NULL DEFAULT 0 COMMENT '当前渠道已销售但尚未逻辑预占的需求'")
    column(database, 'oms_channel_inventory', 'stock_scope_json', "TEXT NULL COMMENT '可售计算使用的虚仓范围JSON'")
    if added:
        execute('UPDATE oms_channel_inventory SET quota_stock=available_stock', database)
        # Explicit schema transition for traced allocations. No runtime compatibility branch.
        execute('''UPDATE oms_channel_inventory c JOIN
          (SELECT company_code,channel_id,sku_sn,SUM(occupied_quantity) qty FROM rule_stock_reservation GROUP BY company_code,channel_id,sku_sn) s
          ON s.company_code=c.company_code AND s.channel_id=c.channel_id AND s.sku_sn=c.sku_sn
          SET c.allocated_stock=c.allocated_stock-s.qty,c.reserved_stock=c.reserved_stock+s.qty''', database)
        execute('''UPDATE oms_inventory i JOIN
          (SELECT company_code,sku_sn,SUM(occupied_quantity) qty FROM rule_stock_reservation GROUP BY company_code,sku_sn) s
          ON s.company_code=i.company_code AND s.sku_sn=i.sku_sn
          SET i.allocated_stock=i.allocated_stock-s.qty,i.reserved_stock=i.reserved_stock+s.qty''', database)
        execute('UPDATE oms_channel_inventory SET available_stock=GREATEST(0,quota_stock+allocated_stock-frozen_stock)', database)

def nacos_get(name):
    url='http://localhost:8848/nacos/v1/cs/configs?'+urllib.parse.urlencode({'dataId':name,'group':'DEFAULT_GROUP'})
    with urllib.request.urlopen(url,timeout=10) as response:return response.read().decode()

def nacos_set(name, content):
    body=urllib.parse.urlencode({'dataId':name,'group':'DEFAULT_GROUP','content':content,'type':'yaml'}).encode()
    with urllib.request.urlopen('http://localhost:8848/nacos/v1/cs/configs',data=body,timeout=10) as response:assert response.read()==b'true'

def main():
    assert list((HERE/'backups').glob('oms-saas-complete-*.sql')), 'Create a local database backup before changing schemas'
    execute(f'CREATE DATABASE IF NOT EXISTS {ORDER_DB} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin')
    execute((HERE/'migrations/20261010_order.sql').read_text(encoding='utf-8'),ORDER_DB)
    from configure_order_shipments import install_order as install_shipments_order
    install_shipments_order(ORDER_DB)
    from configure_order_trade_types import install as install_trade_types
    install_trade_types(ORDER_DB)
    from configure_order_exceptions import install as install_exceptions
    install_exceptions(ORDER_DB)
    install_inventory(INVENTORY_DB)
    parent=ensure_menu('订单管理',0,'oms-order',kind='M',order=14)
    menus=[parent]
    for index,(view,title) in enumerate([('platform','平台订单'),('business','业务订单'),('exceptions','转单异常'),('management','拉单管理')]):
        menu=ensure_menu(title,parent,view,'oms/order/management' if view=='management' else 'oms/order/index',permission='order:order:list',order=index+1)
        execute(f'UPDATE sys_menu SET query={quote("" if view=="management" else json.dumps({"view":view}))} WHERE menu_id={menu}')
        menus.append(menu)
    for index,(title,permission) in enumerate([('查看订单详情','order:order:query'),('查看原始报文','order:order:raw'),('重试转单','order:order:retry'),('配置店铺拉单','order:pull:config'),('补拉与重试任务','order:pull:execute'),('管理推送库','order:source:config')]):
        menus.append(ensure_menu(title,menus[1],'',kind='F',permission=permission,order=20+index))
    for menu in menus:execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')
    from configure_order_settings import install as install_order_settings
    install_order_settings()
    if execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='orderPullTask.scanAll()'").strip()=='0':
        execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark) VALUES ('天猫订单公司店铺扫描','DEFAULT','orderPullTask.scanAll()','0/10 * * * * ?','3','1','0','admin',NOW(),'统一触发oms-order；按公司店铺分页执行，手工补拉不修改自动游标')")
    content=nacos_get('oms-channel-dev.yml').replace('com.oms.channel','com.oms.order').replace('qm_oms_saas_channel',ORDER_DB)
    assert ORDER_DB in content,'Channel service datasource template is unavailable'
    content+='''
oms.order.encryption-key: ${OMS_ORDER_ENCRYPTION_KEY:}
oms.tenant.routes.QM: master
feign.client.config.default.connectTimeout: 5000
feign.client.config.default.readTimeout: 30000
'''
    nacos_set('oms-order-dev.yml',content)
    gateway=nacos_get('ruoyi-gateway-dev.yml')
    if 'id: oms-order' not in gateway:
        gateway=gateway.replace('      routes:\n','''      routes:
        - id: oms-order
          uri: lb://oms-order
          predicates:
            - Path=/order/**
          filters:
            - StripPrefix=1
''')
        assert 'id: oms-order' in gateway
        nacos_set('ruoyi-gateway-dev.yml',gateway)
    compose_path=HERE/'compose.yaml';compose=json.loads(compose_path.read_text(encoding='utf-8'))
    service=copy.deepcopy(compose['services']['channel']);service['container_name']='oms-local-order'
    service['volumes']=['./artifacts/oms-order.jar:/app/app.jar:ro'];service.pop('ports',None)
    service['healthcheck']['test']=['CMD-SHELL','curl -fsS http://localhost:9305/actuator/health | grep -q UP']
    for key in list(service['environment']):
        if key.startswith('OMS_CHANNEL_'):del service['environment'][key]
    service['environment']['OMS_ORDER_ENCRYPTION_KEY']='${OMS_ORDER_ENCRYPTION_KEY:-}'
    compose['services']['order']=service
    compose_path.write_text(json.dumps(compose,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    from configure_business_orders import install_permissions
    install_permissions()
    from configure_order_shipments import install_permissions as install_shipments_permissions
    install_shipments_permissions()
    print('Independent oms-order schema, service, /order gateway route, menus and Quartz trigger installed')

if __name__=='__main__':main()
