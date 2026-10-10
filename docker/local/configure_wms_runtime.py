"""Initialize local WMS encryption key and explicit UI permissions; no warehouse data is inferred."""
import base64
import re
import secrets
from local_db import HERE, execute
from seed_business import ensure_menu

def main():
    env=HERE/'.env'
    content=env.read_text(encoding='utf-8')
    match=re.search(r'^OMS_WMS_ENCRYPTION_KEY=(.*)$',content,re.M)
    if not match or not match.group(1).strip():
        line='OMS_WMS_ENCRYPTION_KEY='+base64.b64encode(secrets.token_bytes(32)).decode()
        content=re.sub(r'^OMS_WMS_ENCRYPTION_KEY=.*$',line,content,flags=re.M) if match else content.rstrip()+'\n'+line+'\n'
        env.write_text(content,encoding='utf-8')
    else:
        assert len(base64.b64decode(match.group(1).strip(),validate=True))==32,'Invalid WMS encryption key'
    parent=int(execute("SELECT menu_id FROM sys_menu WHERE component='oms/simulationStore/index' LIMIT 1").strip())
    for index,(title,permission) in enumerate([('维护仓库对接配置','warehouse:wms:config'),('查看仓库交互日志','warehouse:wms:log'),('查看仓库交互报文','warehouse:wms:payload')]):
        menu=ensure_menu(title,parent,'',kind='F',permission=permission,order=20+index)
        execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')
    print('WMS encryption key ready (not displayed); configuration/log/payload permissions installed')

if __name__=='__main__':main()
