"""Create a separate TEST connection; used historical connection identities stay immutable."""
import json
import secrets
from pathlib import Path
from verify_goods_workspace_ui import login,request

HERE=Path(__file__).resolve().parent
ENDPOINT='http://wms-simulator:8080/qimen/gateway'
NAME='本地奇门模拟仓库（创建与查询）'

def main():
    path=HERE/'wms-simulator-credentials.json'
    if path.exists():credentials=json.loads(path.read_text(encoding='utf8'))
    else:
        credentials=[dict(app_key='LOCAL_QIMEN_QM',customer_id='LOCAL_QM',secret=secrets.token_hex(24))]
        path.write_text(json.dumps(credentials,indent=2),encoding='utf8')
    assert len(credentials)==1
    credential=credentials[0];token=login()
    try:
        connections=request('/supplychain/wmsIntegration/connections',token=token)['data']
        found=[c for c in connections if c['endpoint']==ENDPOINT and c['app_key']==credential['app_key'] and c['customer_id']==credential['customer_id']]
        assert len(found)<=1
        if found:
            connection=found[0];assert connection['environment']=='TEST' and connection['provider']=='QIMEN' and connection['api_version']=='2.0'
        else:
            ident=request('/supplychain/wmsIntegration/connections',dict(name=NAME,provider='QIMEN',api_version='2.0',environment='TEST',endpoint=ENDPOINT,app_key=credential['app_key'],customer_id=credential['customer_id'],secret=credential['secret'],enabled=1),token=token)['data']
            connection=next(c for c in request('/supplychain/wmsIntegration/connections',token=token)['data'] if c['id']==ident)
        safe={k:connection[k] for k in ('id','name','provider','environment','api_version','endpoint')}
        (HERE/'logs/local-qimen-connection.json').write_text(json.dumps(safe,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps(safe,ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
