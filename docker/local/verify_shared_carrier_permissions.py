"""Isolated TEST users exercise server permissions; no existing roles/users are modified."""
import json,secrets,socket,time,urllib.error
from pathlib import Path
from verify_goods_workspace_ui import login,request
from test_multi_virtual_shipping import sql,quote

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/shared-carriers'


def login_as(username,password):
    captcha=request('/code');key=('captcha_codes:'+captcha['uuid']).encode()
    with socket.create_connection(('127.0.0.1',16379),timeout=10) as conn:
        conn.sendall(b'*2\r\n$3\r\nGET\r\n$'+str(len(key)).encode()+b'\r\n'+key+b'\r\n');reader=conn.makefile('rb');length=int(reader.readline()[1:].strip());assert length>0;answer=reader.read(length).decode().strip('"')
    return request('/auth/login',dict(username=username,password=password,uuid=captcha['uuid'],code=answer))['data']['access_token']


def main():
    admin=login();stamp=str(int(time.time()));checks=[];users=[]
    def api(p,b=None,t=None):return request(p,payload=b,token=t or admin)
    def denied(p,b,t):
        try:api(p,b,t)
        except AssertionError as exc:
            assert any(x in str(exc) for x in ['权限','403','无权']),str(exc);return str(exc)[:250]
        except urllib.error.HTTPError as exc:
            assert exc.code in [400,403],exc;return str(exc)
        raise AssertionError('Permission was not enforced: '+p)
    for kind,permissions in [('business',['warehouse:purchaseReturn:query']),('reader',['common:carrier:query'])]:
        name=('SCB_' if kind=='business' else 'SCR_')+stamp;password=secrets.token_urlsafe(12)
        ids=[int(v) for v in sql('SELECT menu_id FROM sys_menu WHERE perms IN ('+','.join(quote(p) for p in permissions)+')','ry-cloud').splitlines()]
        assert ids
        api('/system/role',dict(roleName='[快递TEST]'+kind+' '+stamp,roleKey=name,roleSort=80,status='0',dataScope='1',menuCheckStrictly=True,deptCheckStrictly=True,menuIds=ids))
        role=int(sql('SELECT role_id FROM sys_role WHERE role_key='+quote(name),'ry-cloud'))
        api('/system/user',dict(userName=name,nickName='[公共快递TEST] '+kind,password=password,roleIds=[role],postIds=[],status='0',sex='0',loginCompanyCode='qm',deptId=103))
        token=login_as(name,password);manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));options=api('/supplychain/purchaseReturns/carriers?purchaseId='+str(manifest['ids']['purchase'])+'&flow=OUTBOUND&province=%E6%B5%99%E6%B1%9F',t=token) if kind=='business' else None;assert kind!='business' or options['data']['rows'];rows=api('/order/sharedCarriers/standards/search',t=token)['data']['rows'];assert rows
        blocked=denied('/order/sharedCarriers/standard/save',dict(standard_code='TEST_DENIED_'+stamp,standard_name='[TEST] 无权维护',enabled=1,revision=0,aliases=[],category='EXPRESS'),token)
        assert denied('/order/sharedCarriers/todo/1/retry',dict(revision=1),token)
        if kind=='business':assert denied('/order/sharedCarriers/metadata',None,token)
        else:assert api('/order/sharedCarriers/standard/list',t=token)['data']['rows']
        users.append(dict(username=name,role_id=role,permissions=permissions));checks.append(dict(scenario=('无公共配置权限的业务人员可查询快递选项' if kind=='business' else '公共只读人员可查询配置'),edit_denied=blocked,todo_retry_denied=True))
    (OUT/'permissions.json').write_text(json.dumps(dict(users=users,checks=checks),ensure_ascii=False,indent=2),'utf-8');print('PASS business-only and configuration-reader permissions; edits and retries denied')


if __name__=='__main__':main()

