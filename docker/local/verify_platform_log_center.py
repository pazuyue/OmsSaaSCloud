"""Local TEST acceptance for new platform logs; no production platform requests or stock writes."""
import json
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import wait_for

OUT = Path(__file__).resolve().parents[2] / 'outputs/platform-logs'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    evidence = {}
    try:
        def api(path, body=None):
            return request(path, token=token, payload=body, method='POST' if body is not None else 'GET')
        for attempt in range(60):
            try:
                api('/channel/platform/log-center/options')
                api('/order/exchanges/catalog')
                break
            except Exception:
                if attempt == 59:
                    raise RuntimeError('Platform log services did not become ready')
                time.sleep(1)
        print('Platform log services ready', flush=True)
        shop = api('/channel/platform/shops/51')['data']
        assert shop['simulation'] is True and shop['can_query'] is True, 'Only the retained trusted simulated app may be exercised'
        api('/channel/platform/shops/51/query/shop', {})
        original_exchange = api('/order/exchanges/10')['data']
        assert original_exchange['source_kind'] == 'PLATFORM' and original_exchange['capability']['simulation'] is True
        api('/order/exchanges/10/refresh', {})
        query = {'numberType': 'EXCHANGE', 'number': original_exchange['exchange_sn'], 'allTime': 'true'}
        exchange_logs = api('/channel/platform/log-center?' + urllib.parse.urlencode(query))['data']['rows']
        assert any(row['entry_kind'] == 'SIMULATION' and row['result'] == 'SUCCESS' for row in exchange_logs)
        assert all('request_json' not in row and 'response_json' not in row for row in exchange_logs)
        child = next(row for row in exchange_logs if row['entry_kind'] == 'SIMULATION')
        detail = api('/channel/platform/log-center/' + str(child['id']))['data']
        assert len(detail['chain']) == 2 and not detail['operation_id'] and all(row['environment'] == 'SIMULATION' for row in detail['chain'])
        states = api('/channel/platform/log-center/business-states', [{'type': 'EXCHANGE', 'number': original_exchange['exchange_sn']}])['data']
        assert states['available'] and any(row['id'] == original_exchange['id'] for row in states['rows'])
        assert api('/order/exchanges/10')['data']['state'] == original_exchange['state']
        print('PASS simulated shop/query, exchange aliases and cross-service business progress', flush=True)

        # A read of a nonexistent TEST exchange verifies failure logging without changing a platform document.
        body = {'operation': 'DETAIL', 'args': {'dispute_id': '999999999999999999999999', 'fields': 'dispute_id,status,num'},
                'log_context': {'trigger_source': 'SYSTEM', 'refs': [{'type': 'PLATFORM_EXCHANGE', 'number': '999999999999999999999999'}]}}
        result = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-channel', 'curl', '-sS', '-H', 'from-source: inner', '-H', 'Content-Type: application/json', '--data-binary', '@-', 'http://localhost:9304/exchangePlatform/internal/51/call?company_code=QM'], input=json.dumps(body).encode(), capture_output=True, timeout=40)
        assert result.returncode == 0
        failed = api('/channel/platform/log-center?' + urllib.parse.urlencode({'numberType': 'PLATFORM_EXCHANGE', 'number': body['args']['dispute_id'], 'allTime': 'true'}))['data']['rows']
        assert any(row['result'] == 'FAILED' for row in failed)

        # The retained original SALE shipment already has a TEST package, with no platform task.
        shipment = api('/order/orders/shipments/56')['data']
        assert shipment['shipment_sn'] == 'XSa1ab99142a244e389dd7ac6dbaf7f664'
        assert shipment['business_type'] == 'SALE' and shipment['shipped_quantity'] == 10
        assert all(p['express_code'].startswith('TEST-') for p in shipment['packages'])
        before_outbound = json.dumps(shipment['outbound_records'], sort_keys=True)
        report = api('/order/orders/shipments/56/platform-shipping')['data']
        assert report['configuration']['mode'] == 'SIMULATION'
        if not report['tasks']:
            api('/order/orders/shipments/56/platform-shipping', {})
        report = api('/order/orders/shipments/56/platform-shipping')['data']
        for task in report['tasks']:
            if task['state'] == 'FAILED':
                api('/order/orders/shipments/56/platform-shipping/' + str(task['id']) + '/retry', {})
        if any(t['state'] != 'SUCCESS' for t in report['tasks']):
            jobs = api('/schedule/job/list?pageNum=1&pageSize=100')['rows']
            job = next(j for j in jobs if j['invokeTarget'] == 'fulfillmentScanTask.platformShipping()')
            request('/schedule/job/run', token=token, method='PUT', payload={'jobId': job['jobId'], 'jobGroup': job['jobGroup']})
        print('Simulated platform shipping task retained; existing scan triggered once without changing its status', flush=True)
        report = wait_for(lambda: api('/order/orders/shipments/56/platform-shipping')['data'],
                          lambda r: bool(r['tasks']) and all(t['state'] == 'SUCCESS' for t in r['tasks']),
                          'Simulated shipping task not completed', seconds=150)
        shipping_query = {'numberType': 'SHIPMENT', 'number': shipment['shipment_sn'], 'allTime': 'true'}
        shipping_logs = api('/channel/platform/log-center?' + urllib.parse.urlencode(shipping_query))['data']['rows']
        sends = [row for row in shipping_logs if row['interface_name'] == 'SIMULATION.shipping.send' and row['result'] == 'SUCCESS']
        assert sends and all(row['environment'] == 'SIMULATION' and row['http_status'] is None for row in sends)
        after = api('/order/orders/shipments/56')['data']
        assert after['shipped_quantity'] == 10 and json.dumps(after['outbound_records'], sort_keys=True) == before_outbound
        assert api('/order/exchanges/13')['data']['state'] == 'COMPLETED'
        print('PASS central simulated shipping record; actual outbound facts unchanged', flush=True)

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1150})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + '/oms-channel/platformLogs?' + urllib.parse.urlencode(query), wait_until='networkidle')
            expect(page.get_by_role('heading', name='平台交互日志', exact=True, level=1)).to_be_visible()
            expect(page.get_by_text(original_exchange['exchange_sn'], exact=True).first).to_be_visible()
            rows = page.locator('.platform-log-center > .el-table .el-table__row')
            expect(rows.first).to_be_visible()
            page.screenshot(path=str(OUT / 'exchange-list.png'), animations='disabled')
            page.get_by_role('button', name='查看详情', exact=True).first.click()
            drawer = page.locator('.el-drawer:visible')
            expect(drawer.get_by_text('调用链路', exact=True)).to_be_visible()
            drawer.get_by_role('button', name='查看脱敏报文', exact=True).click()
            expect(drawer.locator('pre').first).to_contain_text('dispute_id')
            page.screenshot(path=str(OUT / 'detail.png'), animations='disabled')
            page.goto(BASE + '/oms-channel/platformLogs?' + urllib.parse.urlencode(shipping_query), wait_until='networkidle')
            expect(page.get_by_text('模拟提交发货', exact=True).first).to_be_visible()
            expect(page.get_by_text('本地模拟，未请求真实平台', exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT / 'shipping-list.png'), animations='disabled')
            page.goto(BASE + '/oms-order/exchanges?exchangeId=10', wait_until='networkidle')
            page.locator('.el-drawer:visible').get_by_role('button', name='平台交互日志', exact=True).click()
            expect(page).to_have_url(__import__('re').compile('/oms-channel/platformLogs.*numberType=EXCHANGE'))
            expect(page.get_by_role('heading', name='平台交互日志', exact=True, level=1)).to_be_visible()
            assert not errors, errors
            browser.close()
        evidence.update(exchange_id=10, exchange_sn=original_exchange['exchange_sn'], exchange_log_ids=[r['id'] for r in exchange_logs],
                        shipment_id=56, shipment_sn=shipment['shipment_sn'], simulated_shipping_logs=[r['id'] for r in sends],
                        failed_read_logs=[r['id'] for r in failed], actual_outbound_unchanged=True,
                        real_platform_requests=False, business_progress_batch_available=True, ui_verified=True)
        (OUT / 'acceptance.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), 'utf-8')
        print('PASS platform log center API, trace, payload, links and browser acceptance', flush=True)
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
