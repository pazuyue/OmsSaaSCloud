"""Install channel platform schema, credential encryption key, and existing RBAC menu actions."""
import base64
import json
import re
import secrets
from local_db import HERE, execute
from seed_business import ensure_menu

def main():
    execute((HERE/'migrations/20261010_channel_platform.sql').read_text(encoding='utf-8'), 'qm_oms_saas_channel')
    if not execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='qm_oms_saas_channel' AND table_name='channel_platform_app' AND column_name='simulation_mode'").strip() == '1':
        execute("ALTER TABLE channel_platform_app ADD simulation_mode TINYINT NOT NULL DEFAULT 0 COMMENT '0真实平台，1本地模拟；模拟不得发送真实平台请求' AFTER enabled", 'qm_oms_saas_channel')
    env=HERE/'.env'
    content=env.read_text(encoding='utf-8')
    match=re.search(r'^OMS_CHANNEL_ENCRYPTION_KEY=(.*)$',content,re.M)
    if not match or not match.group(1).strip():
        line='OMS_CHANNEL_ENCRYPTION_KEY='+base64.b64encode(secrets.token_bytes(32)).decode()
        content=re.sub(r'^OMS_CHANNEL_ENCRYPTION_KEY=.*$',line,content,flags=re.M) if match else content.rstrip()+'\n'+line+'\n'
        env.write_text(content,encoding='utf-8')
    else:
        assert len(base64.b64decode(match.group(1).strip(),validate=True))==32,'Invalid channel encryption key'
    compose=HERE/'compose.yaml'
    data=json.loads(compose.read_text(encoding='utf-8'))
    data['services']['channel']['environment']['OMS_CHANNEL_ENCRYPTION_KEY']='${OMS_CHANNEL_ENCRYPTION_KEY}'
    data['services']['channel']['environment']['OMS_CHANNEL_SIMULATION_ENABLED']='true'
    compose.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    parent=int(execute("SELECT menu_id FROM sys_menu WHERE component='oms/channel/index' LIMIT 1").strip())
    actions=[('查看平台对接','channel:platform:query'),('配置平台应用与店铺','channel:platform:config'),('管理平台授权','channel:platform:authorize'),('查看平台交互日志','channel:platform:log')]
    actions += [('店铺'+label,'channel:channel:'+action) for label,action in [('查询','query'),('新增','add'),('修改','edit'),('删除','remove'),('导出','export')]]
    for i,(title,permission) in enumerate(actions):
        menu=ensure_menu(title,parent,'',kind='F',permission=permission,order=30+i)
        execute(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,{menu} WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu})')
    print('Channel platform schema, encryption key and menu actions ready; no platform credentials fabricated')

if __name__=='__main__':main()
