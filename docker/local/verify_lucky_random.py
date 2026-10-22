"""Retained RANDOM lucky-bag acceptance against local TEST services and WMS simulator.

Only newly owned goods/channel/warehouse/order fixtures are written. No real
platform calls, public scheduler changes, or direct stock balance writes.
"""
import copy
import csv
import io
import json
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import openpyxl
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
import verify_exchange_ownership as ownership
import verify_allocation_rules_v2 as fixture

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/lucky-bags-random'
INV = 'qm_oms_saas_inventory'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ownership.OUT = OUT
    token = login()
    case = ownership.Case('random', token)
    c = case.c

    def api(path, body=None):
        result = request(path, payload=body, token=token, method='POST' if body is not None else 'GET')
        return result.get('data', result)

    def inner(path, body):
        command = ['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-inventory', 'curl', '-fsS', '-X', 'POST',
                   '-H', 'from-source: inner', '-H', 'Content-Type: application/json', '--data-binary', '@-',
                   'http://localhost:9303' + path + '?company_code=QM']
        result = subprocess.run(command, input=json.dumps(body).encode(), capture_output=True, timeout=60)
        assert result.returncode == 0, 'Local TEST inventory service unavailable'
        reply = json.loads(result.stdout)
        assert reply['code'] == 200, reply.get('msg')
        return reply.get('data', reply)

    def snapshot():
        sku_filter = ','.join(quote(c[k]) for k in ['sku', 'a', 'b', 'd'])
        company = "company_code='QM'"
        scoped = company + ' AND sku_sn IN (' + sku_filter + ')'
        fields = {
            'company': ("SELECT sku_sn sku,total_stock total,available_stock available,allocated_stock allocated,reserved_stock reserved,frozen_stock frozen FROM oms_inventory WHERE " + scoped + ' ORDER BY sku_sn', ['sku', 'total', 'available', 'allocated', 'reserved', 'frozen']),
            'warehouse': ("SELECT sku_sn sku,zp_actual_number actual,zp_available_number available,zp_lock_number locked FROM wms_inventory WHERE " + scoped + ' AND store_code=' + quote(c['warehouses'][0]['code']) + ' ORDER BY sku_sn', ['sku', 'actual', 'available', 'locked']),
            'batch': ("SELECT id,sku_sn sku,zp_actual_number actual,zp_available_number available,zp_lock_number locked FROM wms_inventory_batch WHERE " + scoped + ' ORDER BY id', ['id', 'sku', 'actual', 'available', 'locked']),
            'channel': ("SELECT sku_sn sku,available_stock available,allocated_stock allocated,reserved_stock reserved,pending_stock pending FROM oms_channel_inventory WHERE " + scoped + ' AND channel_id=' + str(c['channel_id']) + ' ORDER BY sku_sn', ['sku', 'available', 'allocated', 'reserved', 'pending']),
            'pools': ("SELECT id,quantity,available_quantity available,occupied_quantity occupied,consumed_quantity consumed,released_quantity released,state FROM oms_lucky_pool WHERE " + company + ' AND bag_sku=' + quote(c['sku']) + ' ORDER BY id', ['id', 'quantity', 'available', 'occupied', 'consumed', 'released', 'state']),
            'sources': ("SELECT s.id,s.sku_sn sku,s.batch_id,s.quantity,s.occupied_quantity occupied,s.consumed_quantity consumed,s.released_quantity released FROM oms_lucky_source s JOIN oms_lucky_pool p ON p.company_code=s.company_code AND p.id=s.pool_id WHERE p.company_code='QM' AND p.bag_sku=" + quote(c['sku']) + ' ORDER BY s.id', ['id', 'sku', 'batch_id', 'quantity', 'occupied', 'consumed', 'released']),
            'units': ("SELECT u.id,u.platform_tid tid,u.state,u.components_json components FROM oms_lucky_unit u JOIN oms_lucky_pool p ON p.company_code=u.company_code AND p.id=u.pool_id WHERE p.company_code='QM' AND p.bag_sku=" + quote(c['sku']) + ' ORDER BY u.id', ['id', 'tid', 'state', 'components']),
        }
        # One scoped database round trip; each subquery reads only this fixture.
        entries = []
        for name, (query, keys) in fields.items():
            values = ','.join(quote(k) + ',v.' + k for k in keys)
            entries += [quote(name), '(SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(' + values + ')),JSON_ARRAY()) FROM (' + query + ') v)']
        result = json.loads(sql('SELECT JSON_OBJECT(' + ','.join(entries) + ')', INV))
        for name in fields:
            result[name] = result.get(name) or []
            result[name].sort(key=lambda row: str(row.get('id', row.get('sku', ''))))
        result['totals'] = {key: sum(row[key] for row in result['company'] if row['sku'] != c['sku']) for key in ['total', 'available', 'allocated', 'reserved']}
        result['warehouse_totals'] = {key: sum(row[key] for row in result['warehouse']) for key in ['actual', 'available', 'locked']}
        result['bag_totals'] = {key: sum(row[key] for row in result['pools']) for key in ['quantity', 'available', 'occupied', 'consumed', 'released']}
        for row in result['company']:
            assert min(row[k] for k in ['total', 'available', 'allocated', 'reserved', 'frozen']) >= 0
            assert row['total'] == row['available'] + row['allocated'] + row['reserved'] + row['frozen']
            warehouses = [w for w in result['warehouse'] if w['sku'] == row['sku']]
            assert row['total'] == sum(w['actual'] for w in warehouses), row
            assert row['available'] == sum(w['available'] for w in warehouses), row
            assert row['allocated'] + row['reserved'] == sum(w['locked'] for w in warehouses), row
        for row in result['warehouse']:
            assert row['actual'] == row['available'] + row['locked']
            for key in ['actual', 'available', 'locked']:
                assert row[key] == sum(b[key] for b in result['batch'] if b['sku'] == row['sku'])
        for pool in result['pools']:
            assert pool['quantity'] == sum(pool[k] for k in ['available', 'occupied', 'consumed', 'released'])
        assert sum(r['total'] for r in result['company'] if r['sku'] == c['sku']) == 0
        assert sum(s['quantity'] - s['consumed'] - s['released'] for s in result['sources']) == result['warehouse_totals']['locked']
        return result

    def capture(stage):
        if stage not in c.setdefault('stages', {}):
            value = snapshot()
            c['stages'][stage] = value
            (OUT / (stage + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
            case.save()
        return c['stages'][stage]

    def fingerprint(value):
        return {k: value[k] for k in ['company', 'warehouse', 'batch', 'sources', 'pools']}

    def allocate():
        if c.get('allocated'):
            return
        if not c.get('allocation_id'):
            header = api('/inventory/allocation', {'ruleName': '[随机福袋验收] ' + c['case_id'], 'ruleType': 2,
                         'allocationType': 1, 'ruleRange': 2, 'ruleMode': 2, 'stores': [c['warehouses'][0]['code']],
                         'channels': [{'channelId': c['channel_id'], 'channelName': c['channel_name'], 'ruleType': 2, 'percentage': 6, 'decimalHandleType': 1}]})
            c['allocation_id'] = header['id']; case.save()
            book = openpyxl.Workbook(); book.active.append(['SKU(必填)']); book.active.append([c['sku']])
            stream = io.BytesIO(); book.save(stream); boundary = 'LuckyRandomFixtureBoundary'
            wire = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="random.xlsx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode() + stream.getvalue() + ('\r\n--' + boundary + '--\r\n').encode()
            req = urllib.request.Request(BASE + '/prod-api/inventory/allocation/' + str(header['id']) + '/goods?revision=' + str(header['revision']), data=wire,
                  headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary}, method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=40) as reply:
                imported = json.load(reply)
            assert imported['code'] == 200, imported.get('msg')
            header = api('/inventory/allocation/' + str(header['id']) + '/submit?revision=' + str(imported['data']['revision']), {})
            api('/inventory/allocation/' + str(header['id']) + '/start?revision=' + str(header['revision']) + '&action=EXECUTE', {})
        result = api('/inventory/allocation/' + str(c['allocation_id']) + '/step', {})
        assert result['status'] == 5, result
        c['allocated'] = True; case.save()

    def claim(tid, oid, quantity):
        return inner('/orderStock/internal/lucky-claim', {'channel_id': c['channel_id'], 'platform_tid': tid, 'platform_oid': oid, 'sku_sn': c['sku'], 'quantity': quantity})

    try:
        if not c.get('initialized'):
            c.update(sku='FDR' + c['case_id'], a='FDR' + c['case_id'] + 'A', b='FDR' + c['case_id'] + 'B', d='FDR' + c['case_id'] + 'D',
                     seller='RANDOM_' + c['case_id'], channel_name='[随机福袋验收] ' + c['case_id'], received=True,
                     warehouses=[{'code': 'FDR_V_' + c['case_id'], 'quantity': 0, 'package_count': 1}])
            case.save()
            sample = api('/goods/info/list?pageNum=1&pageSize=1', {})['rows'][0]
            for sku, is_bag in [(c['a'], 0), (c['b'], 0), (c['d'], 0), (c['sku'], 1)]:
                if not api('/goods/info/list?pageNum=1&pageSize=5', {'skuSn': sku})['rows']:
                    api('/goods/info', {'skuSn': sku, 'goodsSn': sku, 'goodsName': '[随机福袋验收] ' + ('销售福袋' if is_bag else '组件 ' + sku[-1]),
                        'categoryCode': sample['categoryCode'], 'colorCode': sample['colorCode'], 'sizeCode': sample['sizeCode'], 'marketPrice': 10, 'validity': 30, 'isFd': is_bag, 'isPackage': 0, 'isGift': 0})
            c['goods_id'] = api('/goods/info/list?pageNum=1&pageSize=5', {'skuSn': c['sku']})['rows'][0]['id']
            rule = api('/goods/info/' + str(c['goods_id']) + '/lucky-bag')
            if rule['revision'] == 0:
                api('/goods/info/' + str(c['goods_id']) + '/lucky-bag/publish', {'revision': 0, 'draft_revision': rule['draft_revision'], 'enabled': 1,
                    'mode': 'RANDOM', 'preset_quantity': 0, 'selection': 'WEIGHTED', 'max_cost': 0,
                    'groups': [{'key': 'top', 'name': '上装', 'candidates': [{'sku_sn': c['a'], 'weight': 1}, {'sku_sn': c['b'], 'weight': 1}]},
                               {'key': 'bottom', 'name': '下装', 'candidates': [{'sku_sn': c['d'], 'weight': 1}]}]})
            c['initialized'] = True; case.save()

        def before_conversion(current, credential):
            if not c.get('components_ready'):
                for index, sku in enumerate([c['a'], c['b'], c['d']], 1):
                    inner('/api/WmsInventory/addInventory', {'relationSn': 'FDRIN' + c['case_id'] + str(index), 'wmsInventoryBatch': {
                        'companyCode': 'QM', 'storeCode': c['warehouses'][0]['code'], 'skuSn': sku,
                        'batchCode': 'FDR-' + c['case_id'] + '-' + str(index), 'zpActualNumber': 10, 'cpActualNumber': 0, 'transactionPrice': 2}})
                c['components_ready'] = True; case.save()
            assert capture('01_received')['totals'] == {'total': 30, 'available': 30, 'allocated': 0, 'reserved': 0}
            allocate()
            protected = capture('02_protected_before_order')
            assert protected['totals'] == {'total': 30, 'available': 18, 'allocated': 12, 'reserved': 0}
            assert protected['bag_totals']['available'] == 6 and len(protected['units']) == 0, 'RANDOM must not pre-generate bag compositions'

        if not c.get('prepared'):
            fixture.MANIFEST = case.path
            fixture.prepare_case(c, token, approve=False, before_conversion=before_conversion, order_quantity=2)
            c['prepared'] = True; case.save()
        registered = capture('03_order_two_bags')
        assert registered['totals'] == {'total': 30, 'available': 18, 'allocated': 8, 'reserved': 4}
        assert registered['bag_totals']['available'] == 4 and registered['bag_totals']['occupied'] == 2
        order = case.order(); assert order['bundles'][0]['quantity'] == 2 and order['bundles'][0]['component_quantity'] == 4
        assert len(order['bundles'][0]['units']) == 2 and len(registered['units']) == 2
        if not c.get('replay_verified'):
            before = snapshot()
            first = claim(c['platform_tid'], c['platform_oid'], 2)
            second = claim(c['platform_tid'], c['platform_oid'], 2)
            assert first == second and first['units'] and fingerprint(before) == fingerprint(snapshot())
            assert {u['unit_id'] for u in first['units']} == {u['unit_id'] for u in order['bundles'][0]['units']}
            c['actual_units'] = first['units']; c['replay_verified'] = True; case.save()

        if not c.get('cancel_verified'):
            cancel_path = OUT / 'cancel-order.json'
            if cancel_path.exists():
                cancel_case = json.loads(cancel_path.read_text(encoding='utf-8'))
            else:
                cancel_case = {k: copy.deepcopy(c[k]) for k in ['case_id', 'created', 'sku', 'seller', 'channel_name', 'channel_id', 'warehouses', 'owner_code', 'real_store_code', 'connection_id', 'received', 'configured']}
                cancel_case.update(platform_tid='993' + c['case_id'], platform_oid='993' + c['case_id'] + '01')
            fixture.MANIFEST = cancel_path
            fixture.prepare_case(cancel_case, token, approve=False, order_quantity=1)
            held = capture('04_cancel_order_reserved')
            assert held['totals'] == {'total': 30, 'available': 18, 'allocated': 6, 'reserved': 6}
            cancel_order = api('/order/orders/business/' + str(cancel_case['order_id']))
            if cancel_order['status'] not in ['VOIDED', 'VOIDING']:
                response = api('/order/orders/business/void-batch', {'orders': [{'id': cancel_order['id'], 'control_version': cancel_order['control_version']}],
                               'confirm_local_void': 1, 'reason': '[随机福袋验收] 取消未发订单，核对原池释放'})
                assert response[0]['submitted'], response
            wait_for(lambda: api('/order/orders/business/' + str(cancel_case['order_id'])), lambda o: o['status'] == 'VOIDED', 'Owned RANDOM cancellation incomplete', 120)
            canceled = capture('05_cancel_returned_to_pool')
            assert canceled['totals'] == registered['totals'] and canceled['warehouse_totals'] == registered['warehouse_totals']
            assert canceled['bag_totals']['available'] == 4 and canceled['bag_totals']['occupied'] == 2
            c.update(cancel_verified=True, cancel_order_id=cancel_case['order_id'], cancel_order_sn=cancel_case['order_sn']); case.save()

        if not c.get('shipped'):
            order = case.allocate()
            assert len(order['shipments']) == 1 and order['shipments'][0]['quantity'] == 4
            shipment = case.shipment(order['shipments'][0]['id'])
            assert all(l['sku_sn'] != c['sku'] and l['source_type'] == 'LUCKY' for l in shipment['lines'])
            allocated = capture('06_warehouse_allocated')
            assert allocated['totals'] == registered['totals'] and allocated['warehouse_totals'] == registered['warehouse_totals']
            case.dispatch_ship(shipment)
            shipped = capture('07_shipped')
            assert shipped['totals'] == {'total': 26, 'available': 18, 'allocated': 8, 'reserved': 0}
            assert shipped['warehouse_totals'] == {'actual': 26, 'available': 18, 'locked': 8}
            assert shipped['bag_totals']['available'] == 4 and shipped['bag_totals']['consumed'] == 2 and shipped['bag_totals']['occupied'] == 0
            c.update(shipped=True, shipment_id=shipment['id'], shipment_sn=shipment['shipment_sn']); case.save()

        if not c.get('outbound_replay_verified'):
            before = snapshot()
            replay = subprocess.run([sys.executable, str(ROOT / 'docker/local/simulate_qimen_delivery.py'), c['shipment_sn']], capture_output=True, text=True, encoding='utf-8', timeout=120)
            assert replay.returncode == 0, replay.stdout[-300:] + replay.stderr[-300:]
            assert fingerprint(before) == fingerprint(snapshot()), 'Repeated WMS event must not deduct inventory twice'
            c['outbound_replay_verified'] = True; case.save()

        if not c.get('platform_report_verified'):
            report = api('/order/orders/shipments/' + str(c['shipment_id']) + '/platform-shipping', {})
            assert sum(int(t['snapshot']['goods'].get(c['platform_oid'], 0)) for t in report['tasks']) == 2
            c['platform_report_verified'] = True; case.save()

        if not c.get('concurrency_verified'):
            if not c.get('race'):
                tids = ['992' + c['case_id'] + str(i) for i in range(8)]
                with ThreadPoolExecutor(max_workers=8) as executor:
                    results = list(executor.map(lambda tid: {'tid': tid, 'oid': tid + '01', 'reply': claim(tid, tid + '01', 1)}, tids))
                c['race'] = results; case.save()
            successful = [r for r in c['race'] if r['reply'].get('units')]
            waiting = [r for r in c['race'] if r['reply'].get('state') == 'WAIT_STOCK']
            assert len(successful) == 4 and len(waiting) == 4, 'Eight concurrent buyers may claim only four remaining bags'
            raced = capture('08_concurrent_four_of_eight')
            assert raced['totals'] == {'total': 26, 'available': 18, 'allocated': 0, 'reserved': 8}
            assert raced['bag_totals']['available'] == 0 and raced['bag_totals']['occupied'] == 4
            for row in successful:
                assert claim(row['tid'], row['oid'], 1) == row['reply'], 'A repeated claim must retain the same original bag'
            for row in waiting + successful:
                inner('/orderStock/internal/cancel', {'channelId': c['channel_id'], 'platformTid': row['tid'],
                      'lines': [{'platformOid': row['oid'], 'skuSn': component['sku_sn'], 'quantity': component['quantity']}
                                for component in row['reply'].get('components', [])]})
            cleaned = capture('09_concurrent_claims_canceled')
            assert fingerprint(cleaned) == fingerprint(c['stages']['07_shipped'])
            c.update(concurrency_verified=True, concurrent_success=len(successful), concurrent_waiting=len(waiting)); case.save()

        if not c.get('reclaimed'):
            result = api('/inventory/lucky-bags/' + c['sku'] + '/release', {'rule_id': c['allocation_id'], 'confirmed': True})
            assert result['state'] == 'RELEASED', result
            final = capture('10_unsold_protection_reclaimed')
            assert final['totals'] == {'total': 26, 'available': 26, 'allocated': 0, 'reserved': 0}
            assert final['warehouse_totals'] == {'actual': 26, 'available': 26, 'locked': 0}
            assert final['bag_totals']['available'] == 0 and final['bag_totals']['consumed'] == 2 and final['bag_totals']['released'] == 4
            c['reclaimed'] = True; case.save()

        order = case.order(); assert order['bundles'][0]['shipped_sets'] == 2
        assert all(u['shipped_complete'] for u in order['bundles'][0]['units'])
        before = {u['unit_id']: [(p['sku_sn'], p['batch_id']) for p in u['components']] for u in c['actual_units']}
        after = {u['unit_id']: [(p['sku_sn'], p['batch_id']) for p in u['components']] for u in order['bundles'][0]['units']}
        assert before == after, 'The actual original composition and batch must stay unchanged through shipping'
        consistency = {}
        for row in snapshot()['company']:
            if row['sku'] == c['sku']:
                continue
            records = api('/inventory/productInventory?skuSn=' + row['sku'])
            selected = next(r for r in records['rows'] if r['skuSn'] == row['sku'])
            detail = api('/inventory/productInventory/' + str(selected['id']))
            assert detail['checkStatus'] == 'CONSISTENT', detail.get('checkMessage')
            consistency[row['sku']] = detail['checkStatus']

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page(); errors = []; page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(BASE + '/oms-goods/lucky-bag/index?id=' + str(c['goods_id']), wait_until='networkidle')
            expect(page.get_by_text('下单时确定内容', exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT / 'random-rule.png'), animations='disabled')
            page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(c['order_id']), wait_until='networkidle')
            page.get_by_role('tab', name='商品明细', exact=True).click()
            expect(page.locator('.sales-bundles').first).to_contain_text('完整发出 2 袋')
            expect(page.locator('.sales-bundles').first).to_contain_text(c['platform_oid'])
            page.screenshot(path=str(OUT / 'order-shipped.png'), animations='disabled')
            assert not errors, errors
            browser.close()

        with (OUT / 'inventory.csv').open('w', encoding='utf-8-sig', newline='') as file:
            writer = csv.writer(file); writer.writerow(['阶段', 'SKU', '商品总库存', '公共可用', '已分配', '订单预占', '仓库实物', '仓库可用', '仓库锁定'])
            for stage, value in c['stages'].items():
                warehouse = {r['sku']: r for r in value['warehouse']}
                for row in value['company']:
                    wh = warehouse.get(row['sku'], {'actual': 0, 'available': 0, 'locked': 0})
                    writer.writerow([stage, row['sku'], row['total'], row['available'], row['allocated'], row['reserved'], wh['actual'], wh['available'], wh['locked']])
        result = {'mode': 'RANDOM', 'order_id': c['order_id'], 'order_sn': c['order_sn'], 'cancel_order_id': c['cancel_order_id'], 'cancel_order_sn': c['cancel_order_sn'],
                  'goods_id': c['goods_id'], 'sku': c['sku'], 'warehouse': c['warehouses'][0]['code'], 'bags_protected': 6, 'bags_shipped': 2, 'pieces_shipped': 4,
                  'units_before_order': 0, 'actual_units': c['actual_units'], 'repeat_claim_keeps_composition_and_stock': True, 'cancel_returns_to_original_pool': True,
                  'duplicate_outbound_deducts_once': True, 'concurrent_requests': 8, 'concurrent_success': 4, 'concurrent_waiting': 4,
                  'all_test_concurrent_claims_canceled': True, 'unsold_bags_reclaimed': 4, 'parent_physical_stock': 0, 'platform_report_bags': 2,
                  'initial_stock': c['stages']['01_received']['totals'], 'final_stock': c['stages']['10_unsold_protection_reclaimed']['totals'],
                  'component_inventory_consistency': consistency, 'real_platform_writes': False, 'browser_errors': errors,
                  'timeline': {stage: {'company': value['totals'], 'warehouse': value['warehouse_totals'], 'bags': value['bag_totals']} for stage, value in c['stages'].items()}}
        (OUT / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('PASS RANDOM: protect 6 bags; freeze at order; ship 2 bags/4 pieces once; cancel restores pool; 8 concurrent claims get only 4 bags; reclaim remaining protection')
        print(json.dumps({k: result[k] for k in ['order_id', 'order_sn', 'sku', 'warehouse', 'initial_stock', 'final_stock', 'concurrent_success', 'concurrent_waiting']}, ensure_ascii=False))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
