"""Install the SkyWalking external link under the existing system monitoring menu."""
import os
from urllib.parse import urlsplit
from local_db import ENV, execute, quote


def install(database='ry-cloud'):
    url=os.environ.get('OMS_TRACING_UI_URL',ENV.get('OMS_TRACING_UI_URL','http://localhost:18090')).strip()
    parsed=urlsplit(url)
    if parsed.scheme not in ('http','https') or not parsed.netloc:
        raise ValueError('OMS_TRACING_UI_URL must be an HTTP(S) console URL')
    parent=execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='monitor' LIMIT 1",database).strip()
    if not parent:
        raise RuntimeError('System monitoring menu is missing')
    existing=execute("SELECT menu_id FROM sys_menu WHERE parent_id="+parent+" AND menu_type='C' AND menu_name='SkyWalking' ORDER BY menu_id LIMIT 1",database).strip()
    if not existing:
        existing=execute("INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) VALUES ('SkyWalking',"+parent+",6,"+quote(url)+",'',0,1,'C','0','0','monitor:skywalking:list','monitor','local-deploy',NOW(),'SkyWalking 链路追踪控制台'); SELECT LAST_INSERT_ID();",database).strip()
    else:
        execute("UPDATE sys_menu SET path="+quote(url)+",order_num=6,component='',is_frame=0 WHERE menu_id="+existing,database)
    execute('INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,'+existing+')',database)
    return int(existing)


if __name__=='__main__':
    print('SkyWalking monitoring menu installed:',install())
