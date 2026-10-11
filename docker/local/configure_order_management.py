"""Install the tested source-config table and encryption key; retain source credentials until verified import."""
import base64, json, secrets
from local_db import HERE, execute
from configure_order import nacos_get,nacos_set
from seed_business import ensure_menu

def main():
    assert list((HERE/'backups').glob('oms-saas-complete-20261011-*.sql')), 'A fresh backup is required'
    execute((HERE/'migrations/20261011_order_management.sql').read_text(encoding='utf-8'),'qm_oms_saas_order')
    from configure_order_trade_types import install as install_trade_types
    install_trade_types('qm_oms_saas_order')
    from configure_order_exceptions import install as install_exceptions
    install_exceptions('qm_oms_saas_order')
    path=HERE/'.env';lines=path.read_text(encoding='utf-8').splitlines()
    if not any(line.startswith('OMS_ORDER_ENCRYPTION_KEY=') for line in lines):
        lines.append('OMS_ORDER_ENCRYPTION_KEY='+base64.b64encode(secrets.token_bytes(32)).decode())
        path.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    content=nacos_get('oms-order-dev.yml')
    if 'oms.order.encryption-key:' not in content:
        nacos_set('oms-order-dev.yml',content+'\noms.order.encryption-key: ${OMS_ORDER_ENCRYPTION_KEY:}\n')
    path=HERE/'compose.yaml';compose=json.loads(path.read_text(encoding='utf-8'))
    compose['services']['order']['environment']['OMS_ORDER_ENCRYPTION_KEY']='${OMS_ORDER_ENCRYPTION_KEY:-}'
    path.write_text(json.dumps(compose,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    parent=execute("SELECT menu_id FROM sys_menu WHERE path='oms-order' AND parent_id=0").strip()
    assert parent.isdigit(),'Order menu must be installed first'
    platform=execute("SELECT menu_id FROM sys_menu WHERE parent_id="+parent+" AND path='platform'").strip()
    assert platform.isdigit(),'Platform order menu must be installed first'
    existing=execute("SELECT menu_id FROM sys_menu WHERE parent_id="+parent+" AND path IN ('tasks','management')").strip()
    assert not existing or existing.isdigit(),'Duplicate management entries need review'
    if existing:
        execute("UPDATE sys_menu SET menu_name='拉单管理',path='management',component='oms/order/management',query='',order_num=4 WHERE menu_id="+existing)
    else:
        existing=str(ensure_menu('拉单管理',int(parent),'management','oms/order/management',permission='order:order:list',order=4))
    execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{existing} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={existing})')
    permission=ensure_menu('管理推送库',int(platform),'',kind='F',permission='order:source:config',order=25)
    execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{permission} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={permission})')
    print('Push-source table and order encryption configuration installed; existing credentials retained for import')
if __name__=='__main__':main()
