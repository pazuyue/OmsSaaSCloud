"""Check business queries, imported counts, tenant binding, routes and dictionaries."""
import json
import subprocess
import urllib.request
from local_db import HERE, execute

BASE = 'http://localhost:8088/prod-api'
def request(path, payload=None, token=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode() if payload is not None else None, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        body = json.load(response)
    if body.get('code') != 200:
        raise AssertionError((path, body))
    return body

def main():
    captcha = request('/code')
    code = subprocess.check_output(['docker', 'exec', 'oms-local-redis', 'redis-cli', '--raw', 'GET', 'captcha_codes:' + captcha['uuid']], text=True).strip().strip('"')
    token = request('/auth/login', {'username': 'admin', 'password': 'admin123', 'uuid': captcha['uuid'], 'code': code})['data']['access_token']
    failures = []
    try:
        user = request('/system/user/getInfo', token=token)
        assert user['user']['loginCompanyCode'] == 'qm'
        print('PASS admin company qm', flush=True)
        checks = [
            ('/goods/info/list', {}, 'qm_oms_saas_commodity', 'goods_sku_sn_info'),
            ('/goods/category/list', None, 'qm_oms_saas_commodity', 'goods_category'),
            ('/goods/color/list', None, 'qm_oms_saas_commodity', 'goods_color'),
            ('/goods/size/list', None, 'qm_oms_saas_commodity', 'goods_size'),
            ('/supplychain/supplier/list', None, 'qm_oms_saas_commodity', 'supplier_info'),
            ('/supplychain/owner/list', None, 'qm_oms_saas_commodity', 'owner_info'),
            ('/supplychain/realStore/list', None, 'qm_oms_saas_commodity', 'wms_real_store_info'),
            ('/supplychain/simulationStore/list', None, 'qm_oms_saas_commodity', 'wms_simulation_store_info'),
            ('/supplychain/poInfo/list', None, 'qm_oms_saas_commodity', 'po_info'),
            ('/supplychain/noTickets/list', None, 'qm_oms_saas_commodity', 'no_tickets'),
            ('/supplychain/tickets/list', None, 'qm_oms_saas_commodity', 'wms_tickets'),
            ('/inventory/wmsInventory/list', None, 'qm_oms_saas_inventory', 'wms_inventory'),
            ('/inventory/wmsInventory/wmsInventoryBatch/list', None, 'qm_oms_saas_inventory', 'wms_inventory_batch'),
            ('/inventory/ruleStock/list', None, 'qm_oms_saas_inventory', 'rule_stock_info'),
            ('/channel/channel/list', None, 'qm_oms_saas_channel', 't_channel'),
            ('/system/companyModelAssociationConfig/list', None, 'ry-cloud', 'sys_company_model_association_config'),
        ]
        for path, payload, schema, table in checks:
            try:
                body = request(path, payload, token)
                count = int(execute('SELECT COUNT(*) FROM `' + table + '`', schema).strip())
                rows = body.get('rows', body.get('data', []))
                assert len(rows) == count, (path, len(rows), count)
                print('PASS', path, 'rows=' + str(count), flush=True)
            except Exception as error:
                failures.append(str(error))
                print('FAIL', error, flush=True)
        for key, definition in json.loads((HERE / 'business-dictionaries.json').read_text(encoding='utf-8')).items():
            body = request('/system/dict/data/type/' + key, token=token)
            actual = {x['dictValue'] for x in body['data']}
            assert {x[0] for x in definition['values']} <= actual, key
        print('PASS 18 business dictionaries', flush=True)
        def components(routes):
            for route in routes:
                if route.get('component'):
                    yield route['component']
                yield from components(route.get('children', []))
        found = set(components(request('/system/menu/getRouters', token=token)['data']))
        expected = execute("SELECT component FROM sys_menu WHERE menu_type='C' AND create_by='local-deploy'").splitlines()
        assert set(expected) <= found, set(expected) - found
        print('PASS', len(expected), 'business menu routes', flush=True)
        plugin = request('/goods/goodsAdministration/goodsTest', {}, token)
        assert plugin['data']['data']['companyCode'] == 'qm', plugin
        print('PASS goods-to-system plugin lookup', flush=True)
    finally:
        request('/auth/logout', {}, token, method='DELETE')
    if failures:
        raise SystemExit('\n'.join(failures))

if __name__ == '__main__':
    main()
