"""Read-only acceptance of deployed channel inventory APIs and menu."""
import json
import subprocess
import urllib.error
import urllib.request
from urllib.parse import urlencode
from verify_business import request

def main():
    captcha = request('/code')
    answer = subprocess.check_output(['docker', 'exec', 'oms-local-redis', 'redis-cli', '--raw', 'GET', 'captcha_codes:' + captcha['uuid']], text=True).strip().strip('"')
    token = request('/auth/login', {'username': 'admin', 'password': 'admin123', 'uuid': captcha['uuid'], 'code': answer})['data']['access_token']
    try:
        menus = request('/system/menu/getRouters', token=token)['data']
        def leaves(rows):
            for row in rows:
                yield row
                yield from leaves(row.get('children', []))
        assert any(row.get('component') == 'oms/channelInventory/index' for row in leaves(menus)), 'Channel stock menu missing'
        page = request('/inventory/channelInventory?pageSize=20', token=token)
        print('PASS channel stock menu and list:', page['total'], 'rows')
        assert len(page['rows']) <= 20
        for row in page['rows'][:3]:
            params = urlencode({'channelId': row['channelId'], 'skuSn': row['skuSn']})
            filtered = request('/inventory/channelInventory?' + params, token=token)
            assert all(r['channelId'] == row['channelId'] and r['skuSn'] == row['skuSn'] for r in filtered['rows'])
            detail = request('/inventory/channelInventory/' + str(row['id']), token=token)['data']
            assert detail['id'] == row['id'] and 'reservationSummary' in detail
            for tab in ['sources', 'executions', 'orders', 'events']:
                result = request(f"/inventory/channelInventory/{row['id']}/{tab}?pageSize=1", token=token)
                assert len(result['rows']) <= 1
                print('PASS', row['id'], tab, 'records:', result['total'])
            request('/inventory/channelInventory/channels?' + urlencode({'ids': row['channelId']}), token=token)
            request('/goods/info/inventoryLookup?' + urlencode({'skus': row['skuSn']}), token=token)
        request('/inventory/channelInventory?onlyStock=true&pageSize=1000', token=token)
        url = 'http://localhost:8088/prod-api/inventory/channelInventory'
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                body = json.load(response)
                assert body['code'] == 401, body
        except urllib.error.HTTPError as error:
            assert error.code == 401, error.code
        print('PASS unauthenticated access rejected; filters, pagination, drilldowns and metadata available')
    finally:
        request('/auth/logout', {}, token, method='DELETE')

if __name__ == '__main__':
    main()
