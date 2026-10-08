"""Read-only regression checks for the warehouse batch detail endpoint."""
import subprocess
from urllib.parse import urlencode
from local_db import execute
from verify_business import request

ENDPOINT = '/inventory/wmsInventory/wmsInventoryBatch/list'

def main():
    captcha = request('/code')
    code = subprocess.check_output(['docker', 'exec', 'oms-local-redis', 'redis-cli', '--raw', 'GET', 'captcha_codes:' + captcha['uuid']], text=True).strip().strip('"')
    token = request('/auth/login', {'username': 'admin', 'password': 'admin123', 'uuid': captcha['uuid'], 'code': code})['data']['access_token']
    try:
        original = {'storeCode': 'VC0001', 'skuSn': 'test01', 'companyCode': 'QM'}
        result = request(ENDPOINT + '?' + urlencode(original), token=token)
        expected = int(execute("SELECT COUNT(*) FROM wms_inventory_batch WHERE store_code='VC0001' AND sku_sn='test01' AND company_code='QM'", 'qm_oms_saas_inventory').strip())
        assert result['total'] == expected
        print('PASS original request: code=200, total=' + str(result['total']), flush=True)

        record = execute('SELECT store_code,sku_sn,company_code FROM wms_inventory_batch WHERE company_code IS NOT NULL LIMIT 1', 'qm_oms_saas_inventory').strip()
        if not record:
            raise AssertionError('A batch row is required to verify positive matching')
        store, sku, company = record.split('\t')
        params = {'storeCode': store, 'skuSn': sku, 'companyCode': company}
        result = request(ENDPOINT + '?' + urlencode(params), token=token)
        assert result['rows'], 'Existing batch was not returned'
        assert all(row['storeCode'] == store and row['skuSn'] == sku and row['companyCode'].lower() == company.lower() for row in result['rows'])
        print('PASS existing batch and warehouse/SKU/company matching', flush=True)
        for field, value in [('storeCode', store[:-1]), ('skuSn', sku[:-1]), ('companyCode', 'NO_MATCH_UI_CHECK')]:
            query = {**params, field: value}
            result = request(ENDPOINT + '?' + urlencode(query), token=token)
            assert result['total'] == 0, (field, 'Unexpected batch from a different inventory dimension')
            print('PASS unmatched ' + field + ' returns an empty list', flush=True)
    finally:
        request('/auth/logout', {}, token, method='DELETE')

if __name__ == '__main__':
    main()
