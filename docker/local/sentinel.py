"""Prepare and run the local Sentinel dashboard without restarting business services."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

HERE=Path(__file__).resolve().parent
VERSION='1.8.10'
FILENAME=f'sentinel-dashboard-{VERSION}.jar'
URL=f'https://github.com/alibaba/Sentinel/releases/download/{VERSION}/{FILENAME}'
# Official release digest: https://github.com/alibaba/Sentinel/releases/expanded_assets/1.8.10
SHA256='9e0d11bc30006c9f70cde982b456fc7bd03b94ec9ab5c45c034eb66b968ca19e'


def service():
    return {
        'image':'${JAVA_RUNTIME_IMAGE:-docker_ruoyi-auth:latest}',
        'container_name':'oms-local-sentinel',
        'entrypoint':['sh','-c','exec java -Dfile.encoding=UTF-8 -Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -Dserver.port=8718 -Dcsp.sentinel.dashboard.server=127.0.0.1:8718 -Dproject.name=sentinel-dashboard -jar /app/sentinel-dashboard.jar'],
        'working_dir':'/app',
        'environment':{
            'TZ':'Asia/Shanghai',
            'SENTINEL_DASHBOARD_AUTH_USERNAME':'${SENTINEL_DASHBOARD_USERNAME:-sentinel}',
            'SENTINEL_DASHBOARD_AUTH_PASSWORD':'${SENTINEL_DASHBOARD_PASSWORD:-sentinel}'
        },
        'ports':['127.0.0.1:8718:8718'],
        'volumes':[f'./artifacts/{FILENAME}:/app/sentinel-dashboard.jar:ro'],
        'mem_limit':'512m',
        'healthcheck':{'test':['CMD-SHELL','curl -fsS http://127.0.0.1:8718/ >/dev/null'],'interval':'10s','timeout':'5s','retries':18,'start_period':'20s'},
        'restart':'unless-stopped',
        'logging':{'driver':'json-file','options':{'max-size':'10m','max-file':'3'}}
    }


def install_menu():
    from local_db import execute,quote
    parent=execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='monitor' LIMIT 1").strip()
    if not parent:raise RuntimeError('System monitoring menu is missing')
    menu=execute("SELECT menu_id FROM sys_menu WHERE parent_id="+parent+" AND menu_name='Sentinel控制台' AND menu_type='C' LIMIT 1").strip()
    if not menu:
        menu=execute("INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) VALUES ('Sentinel控制台',"+parent+",3,'http://localhost:8718','',0,1,'C','0','0','monitor:sentinel:list','sentinel','local-deploy',NOW()); SELECT LAST_INSERT_ID();").strip()
    else:
        execute("UPDATE sys_menu SET path="+quote('http://localhost:8718')+",is_frame=0,component='' WHERE menu_id="+menu)
    execute('INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,'+menu+')')


def prepare():
    artifacts=HERE/'artifacts';artifacts.mkdir(exist_ok=True)
    target=artifacts/FILENAME
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=SHA256:
        partial=artifacts/(FILENAME+'.part')
        print('Downloading official Sentinel dashboard',VERSION,flush=True)
        subprocess.run(['curl','-fL','--silent','--show-error','--connect-timeout','15','--max-time','180','--retry','2','-o',str(partial),URL],check=True)
        if hashlib.sha256(partial.read_bytes()).hexdigest()!=SHA256:
            raise RuntimeError('Sentinel dashboard SHA-256 verification failed')
        partial.replace(target)
    compose=HERE/'compose.yaml'
    data=json.loads(compose.read_text())
    data['services']['sentinel']=service()
    compose.write_text(json.dumps(data,indent=2)+'\n')
    print('Sentinel artifact verified; local Compose service prepared',flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','enable'])
    args=parser.parse_args()
    prepare()
    if args.action=='enable':
        install_menu()
        subprocess.run(['docker','compose','-f','compose.yaml','config','--quiet'],cwd=HERE,check=True)
        subprocess.run(['docker','compose','-f','compose.yaml','up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','180','sentinel'],cwd=HERE,check=True)
        print('Sentinel dashboard ready: http://localhost:8718')


if __name__=='__main__':main()
