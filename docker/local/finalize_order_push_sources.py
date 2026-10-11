"""Remove runtime environment sources only after the encrypted import has been verified."""
import json
from local_db import HERE, execute
from configure_order import nacos_get, nacos_set

def main():
    count=execute("SELECT COUNT(*) FROM oms_order_push_source WHERE company_code='QM' AND source_key IN ('sysinfo100','sysinfo113') AND enabled=1 AND last_test_status='SUCCESS' AND LENGTH(password_cipher)>20",'qm_oms_saas_order').strip()
    assert count=='2','Both encrypted sources must be imported and tested first'
    content=nacos_get('oms-order-dev.yml')
    start=content.find('\norders:\n  push:\n')
    if start>=0:
        end=content.index('\nfeign.client.config.default.connectTimeout:',start)
        content=content[:start]+content[end:]
    assert 'oms.order.encryption-key:' in content
    nacos_set('oms-order-dev.yml',content)
    path=HERE/'compose.yaml'
    compose=json.loads(path.read_text(encoding='utf-8'))
    env=compose['services']['order']['environment']
    for key in list(env):
        if key.startswith('OMS_ORDER_PUSH_'): del env[key]
    assert 'OMS_ORDER_ENCRYPTION_KEY' in env
    path.write_text(json.dumps(compose,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Verified encrypted sources; obsolete runtime source environment binding removed')

if __name__=='__main__':main()
