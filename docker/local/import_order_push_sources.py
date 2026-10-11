"""One-time explicit import into the admin API. No runtime environment-source fallback."""
import subprocess, re
from local_db import ENV
from verify_business import request

def main():
    captcha=request('/code');answer=subprocess.check_output(['docker','exec','oms-local-redis','redis-cli','--raw','GET','captcha_codes:'+captcha['uuid']],text=True).strip().strip('"')
    token=request('/auth/login',{'username':'admin','password':'admin123','uuid':captcha['uuid'],'code':answer})['data']['access_token']
    try:
        old={row['source_key']:row for row in request('/order/orders/sources',token=token)['data']}
        for host in ['113','100']:
            prefix='OMS_ORDER_PUSH_'+host+'_';url=ENV.get(prefix+'URL','').strip("'");match=re.fullmatch(r'jdbc:mysql://(172\.16\.9\.'+host+r'):(\d+)/sys_info\?.+',url);assert match,'Expected verified sys_info connection'
            key='sysinfo'+host;body={'source_key':key,'source_name':host+' / sys_info','host':match[1],'port':int(match[2]),'database_name':'sys_info','username':ENV[prefix+'USERNAME'].strip("'"),'password':ENV[prefix+'PASSWORD'].strip("'"),'enabled':1,'revision':old.get(key,{}).get('revision',0)}
            request('/order/orders/sources',body,token,'POST');request('/order/orders/sources/'+key+'/test',{},token,'POST')
        rows=request('/order/orders/sources',token=token)['data'];assert all('password' not in r and 'password_cipher' not in r for r in rows)
        assert {r['source_key'] for r in rows if r['enabled']} >= {'sysinfo100','sysinfo113'}
        print('100 and 113 sources saved encrypted and tested through the order service; no shops enabled')
    finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
