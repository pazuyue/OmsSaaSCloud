"""Owned local TEST fixtures: product forms, composite gifts, stock sources and UI.

Receives through the inventory API, reserves and allocates through normal OMS
flows, then voids the owned order. No warehouse dispatch or platform writes.
"""
import io
import json
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path
import openpyxl
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
import verify_allocation_rules_v2 as fixture

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/gift-products'
INV, GOODS, ORDER = 'qm_oms_saas_inventory', 'qm_oms_saas_commodity', 'qm_oms_saas_order'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / 'case.json'
    if path.exists():
        c = json.loads(path.read_text('utf-8'))
    else:
        stamp = datetime.now().strftime('%Y%m%d%H%M%S')
        prefix = 'CG' + stamp
        c = {'case_id': stamp, 'prefix': prefix, 'created': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
             'sku': prefix + 'A', 'b': prefix + 'B', 'd': prefix + 'D', 'kit': prefix + 'K', 'stocked': prefix + 'S',
             'random': prefix + 'R', 'preset': prefix + 'P', 'seller': prefix, 'channel_name': '[复合赠品验收] ' + stamp,
             'platform_tid': '993' + stamp, 'platform_oid': '993' + stamp + '01',
             'warehouses': [{'code': prefix + 'V', 'quantity': 10, 'package_count': 0}], 'stages': {}}
    token, errors, checks = login(), [], []

    def save():
        path.write_text(json.dumps(c, ensure_ascii=False, indent=2), 'utf-8')

    def api(route, body=None, method=None):
        result = request(route, payload=body, token=token, method=method or ('POST' if body is not None else 'GET'))
        assert result['code'] == 200, result.get('msg')
        return result.get('data', result)

    def check(message):
        checks.append(message)
        print('PASS ' + message, flush=True)

    def inner(route, body):
        result = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-inventory', 'curl', '-fsS',
                                 '-H', 'from-source: inner', '-H', 'Content-Type: application/json', '--data-binary', '@-',
                                 'http://localhost:9303' + route + '?company_code=QM'],
                                input=json.dumps(body).encode(), capture_output=True, timeout=60)
        assert result.returncode == 0, 'Local inventory request failed'
        reply = json.loads(result.stdout)
        assert reply['code'] == 200, reply.get('msg')
        return reply.get('data', reply)

    def receive(sku, quantity):
        return inner('/api/WmsInventory/addInventory', {'relationSn': 'CGIN' + sku, 'wmsInventoryBatch': {
            'companyCode': 'QM', 'storeCode': c['warehouses'][0]['code'], 'skuSn': sku,
            'batchCode': 'CG-' + sku, 'zpActualNumber': quantity, 'cpActualNumber': 0, 'transactionPrice': 2}})

    def snapshot():
        skus = ','.join(quote(c[k]) for k in ['sku', 'b', 'd', 'kit', 'stocked', 'random', 'preset'])
        result = json.loads(sql("SELECT JSON_OBJECT('total',SUM(total_stock),'available',SUM(available_stock),'allocated',SUM(allocated_stock),'reserved',SUM(reserved_stock)) FROM oms_inventory WHERE company_code='QM' AND sku_sn IN (" + skus + ')', INV))
        result['warehouse'] = json.loads(sql("SELECT JSON_OBJECT('actual',SUM(zp_actual_number),'available',SUM(zp_available_number),'locked',SUM(zp_lock_number)) FROM wms_inventory WHERE company_code='QM' AND store_code=" + quote(c['warehouses'][0]['code']) + ' AND sku_sn IN (' + skus + ')', INV))
        result['pools'] = json.loads(sql("SELECT JSON_OBJECT('available',SUM(available_quantity),'occupied',SUM(occupied_quantity),'consumed',SUM(consumed_quantity)) FROM oms_lucky_pool WHERE company_code='QM' AND bag_sku IN (" + quote(c['random']) + ',' + quote(c['preset']) + ')', INV))
        return result

    def bag_allocation(sku, mode):
        if c.get(mode + '_allocation'):
            return
        h = api('/inventory/allocation', {'ruleName': '[复合赠品验收] ' + mode + c['case_id'], 'ruleType': 2, 'allocationType': 1,
                'ruleRange': 2, 'ruleMode': 2, 'stores': [c['warehouses'][0]['code']],
                'channels': [{'channelId': c['channel_id'], 'channelName': c['channel_name'], 'ruleType': 2, 'percentage': 2, 'decimalHandleType': 1}]})
        book = openpyxl.Workbook(); book.active.append(['SKU(必填)']); book.active.append([sku])
        stream = io.BytesIO(); book.save(stream); boundary = 'CompositeGiftFixtureBoundary'
        wire = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="gifts.xlsx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode() + stream.getvalue() + ('\r\n--' + boundary + '--\r\n').encode()
        req = urllib.request.Request(BASE + '/prod-api/inventory/allocation/' + str(h['id']) + '/goods?revision=' + str(h['revision']), data=wire,
                                     headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary}, method='POST')
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=40) as reply:
            imported = json.load(reply)
        assert imported['code'] == 200, imported.get('msg')
        h = api('/inventory/allocation/' + str(h['id']) + '/submit?revision=' + str(imported['data']['revision']), {})
        api('/inventory/allocation/' + str(h['id']) + '/start?revision=' + str(h['revision']) + '&action=EXECUTE', {})
        result = api('/inventory/allocation/' + str(h['id']) + '/step', {})
        assert result['status'] == 5, result
        c[mode + '_allocation'] = h['id']; save()

    def prepare_gifts(current, credential):
        if c.get('gifts_ready'):
            return
        sample = api('/goods/info/list?pageNum=1&pageSize=1', {})['rows'][0]
        c['goods_ids'] = {}
        for key, kind in [('sku', 'NORMAL'), ('b', 'NORMAL'), ('d', 'NORMAL'), ('kit', 'BUNDLE'), ('stocked', 'BUNDLE'), ('random', 'LUCKY_BAG'), ('preset', 'LUCKY_BAG')]:
            found = api('/goods/info/list?pageNum=1&pageSize=1', {'skuSn': c[key]})['rows']
            body = {'skuSn': c[key], 'goodsSn': c['prefix'], 'goodsName': '[复合赠品验收] ' + key,
                    'categoryCode': sample['categoryCode'], 'colorCode': sample['colorCode'], 'sizeCode': sample['sizeCode'],
                    'marketPrice': 10, 'validity': 30, 'productType': kind, 'isGift': 0 if key in ['b', 'd'] else 1}
            if found:
                body['id'] = found[0]['id']; api('/goods/info', body, 'PUT'); ident = body['id']
            else:
                ident = api('/goods/info', body)
            c['goods_ids'][key] = ident
        for key, mode in [('kit', 'VIRTUAL'), ('stocked', 'STOCKED')]:
            if not api('/goods/info/' + str(c['goods_ids'][key]) + '/bundle')['revision']:
                api('/goods/info/' + str(c['goods_ids'][key]) + '/bundle', {'revision': 0, 'enabled': 1, 'inventory_mode': mode,
                    'components': [{'sku_sn': c['sku'], 'quantity': 2 if mode == 'VIRTUAL' else 1}, {'sku_sn': c['b'], 'quantity': 1}]})
        for key, mode in [('random', 'RANDOM'), ('preset', 'PRESET')]:
            rule = api('/goods/info/' + str(c['goods_ids'][key]) + '/lucky-bag')
            if not rule['revision']:
                api('/goods/info/' + str(c['goods_ids'][key]) + '/lucky-bag/publish', {'revision': 0, 'draft_revision': rule['draft_revision'], 'enabled': 1,
                    'mode': mode, 'selection': 'WEIGHTED', 'max_cost': 0, 'preset_quantity': 2 if mode == 'PRESET' else 0,
                    'groups': [{'key': 'top', 'name': '上装', 'candidates': [{'sku_sn': c['sku'], 'weight': 1}, {'sku_sn': c['b'], 'weight': 1}]},
                               {'key': 'bottom', 'name': '下装', 'candidates': [{'sku_sn': c['d'], 'weight': 1}]}]})
        for key, qty in [('b', 10), ('d', 10), ('stocked', 3)]:
            receive(c[key], qty)
        bag_allocation(c['random'], 'random'); bag_allocation(c['preset'], 'preset')
        c['stages']['before'] = snapshot()
        gifts = [{'sku_sn': c[key], 'goods_name': '[复合赠品验收] ' + key, 'quantity': 1} for key in ['sku', 'kit', 'stocked', 'random', 'preset']]
        group = api('/order/gifts/groups', {'name': '[复合赠品验收] ' + c['case_id'], 'enabled': 1, 'policy': {
            'start_time': (datetime.now() - timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'),
            'end_time': (datetime.now() + timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'), 'shipped_policy': 'RETURN', 'allow_waive': True,
            'difference_affects_amount': False, 'rules': [{'key': 'G', 'name': '购买赠送多种形态', 'type': 'PRODUCT',
                'minimum': 1, 'cap': 1, 'max_count': 1, 'skus': [c['sku']], 'amount': '0', 'gifts': gifts, 'tiers': [], 'stop': True}]}})
        c['gift_group'] = group['id']
        c['shop_group'] = api('/order/gifts/shop-groups', {'name': '[复合赠品验收] ' + c['case_id'], 'enabled': 1, 'channels': [c['channel_id']], 'links': [{'group_id': group['id'], 'continue_after_match': False}]})['id']
        trade = {'tid': c['platform_tid'], 'status': 'WAIT_SELLER_SEND_GOODS', 'type': 'fixed', 'seller_nick': c['seller'],
                 'created': c['created'], 'modified': c['created'], 'pay_time': c['created'], 'payment': '10.00', 'post_fee': '0',
                 'receiver_name': '复合赠品测试', 'receiver_mobile': 'TEST-MASKED', 'receiver_state': '北京市', 'receiver_city': '北京市',
                 'receiver_district': '验收区', 'receiver_address': '验收地址，无实际配送', 'orders': {'order': [{'oid': c['platform_oid'],
                 'outer_sku_id': c['sku'], 'num': 1, 'payment': '10.00', 'divide_order_fee': '10.00', 'price': '10.00', 'title': '[复合赠品验收] 购买商品'}]}}
        payload = json.dumps({'trade_fullinfo_get_response': {'trade': trade}}, ensure_ascii=False)
        import hashlib
        digest = hashlib.sha256(payload.encode()).hexdigest()
        sql("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM'," + str(c['channel_id']) + ',' + ','.join(quote(x) for x in [c['platform_tid'], 'WAIT_SELLER_SEND_GOODS', 'fixed', c['seller'], c['created'], c['created'], c['created'], digest, payload, 'WAITING']) + ')', ORDER)
        c['gifts_ready'] = True; save()

    try:
        fixture.MANIFEST = path
        if not c.get('prepared'):
            fixture.prepare_case(c, token, approve=False, before_conversion=prepare_gifts, order_quantity=1)
            c['prepared'] = True; save()
        order = lambda: api('/order/orders/business/' + str(c['order_id']))
        if not c.get('verified'):
            o = order(); gift = api('/order/gifts/orders/' + str(c['order_id']))
            assert len(gift['parents']) == 5 and sorted(p['sales_unit'] for p in gift['parents']) == ['件', '套', '套', '袋', '袋']
            assert len([l for l in o['lines'] if not l.get('gift_plan_id')]) == 1
            assert all(l['platform_oid'] == '' and str(l['stock_reference']).startswith('GIFT:') and l['payment'] == 0 for l in o['lines'] if l.get('gift_plan_id'))
            assert len(o['bundles']) == 3 and all(p['gift_plan_id'] for p in o['bundles'])
            c['stages']['reserved'] = snapshot(); save()
            assert c['stages']['reserved']['total'] == 33 and c['stages']['reserved']['reserved'] == 10
            assert c['stages']['reserved']['pools']['available'] == 2 and c['stages']['reserved']['pools']['occupied'] == 2
            before = snapshot(); frozen = {p['sku_sn']: p.get('bundle_snapshot') for p in gift['parents']}
            assert not api('/order/orders/business/reserve-batch', [c['order_id']])[0]['submitted']
            groups = {}
            for line in o['lines']:
                groups.setdefault(line.get('stock_reference') or c['platform_tid'], []).append(line)
            for reference, lines in groups.items():
                inner('/orderStock/internal/reserve', {'channelId': c['channel_id'], 'platformTid': reference, 'allowPublicStock': True,
                    'lines': [{'platformOid': line['platform_oid'], 'skuSn': line['sku_sn'], 'quantity': line['quantity'],
                               'componentQuantity': line.get('component_quantity', 0), 'gift': bool(line.get('gift_plan_id'))} for line in lines]})
            for parent in gift['parents']:
                if (parent.get('bundle_snapshot') or {}).get('business_type') == 'LUCKY_BAG':
                    replay = inner('/orderStock/internal/lucky-claim', {'channel_id': c['channel_id'], 'platform_tid': parent['stock_reference'],
                        'platform_oid': '', 'sku_sn': parent['sku_sn'], 'quantity': parent['quantity'], 'gift': True})
                    assert replay == parent['bundle_snapshot']
            wait_for(lambda: order()['stock_attempt_status'], lambda state: state == 'DONE', 'Stock retry not completed')
            assert snapshot() == before
            assert {p['sku_sn']: p.get('bundle_snapshot') for p in api('/order/gifts/orders/' + str(c['order_id']))['parents']} == frozen
            check('真实转单：五种赠送形态、同SKU购买与赠送隔离，重试组成及库存不变')
            o = order()
            if o['review_status'] != 'APPROVED':
                assert api('/order/orders/business/approve-batch', {'orders': [{'id': o['id'], 'control_version': o['control_version']}], 'reason': '[验收] 复合赠品库存核对'})[0]['success']
            o = order()
            if o['allocation_status'] != 'FULL':
                result = api('/order/orders/business/allocate-batch', {'items': [{'id': o['id'], 'control_version': o['control_version']}]})[0]
                assert result['success'], result
            o = order(); assert o['allocation_quantity'] == 10 and o['allocation_status'] == 'FULL'
            assert all(s['wms_dispatch_state'] == 'NONE' and s['shipped_quantity'] == 0 for s in o['shipments'])
            c['stages']['allocated'] = snapshot(); save()
            assert c['stages']['allocated']['warehouse']['locked'] == 14
            check('真实分仓：套装按原库存方式、福袋按原保障批次占用，未下发仓库')
            with sync_playwright() as p:
                browser = p.chromium.launch(headless=True)
                context = browser.new_context(viewport={'width': 1500, 'height': 1050})
                context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
                page = context.new_page(); page.on('pageerror', lambda error: errors.append(str(error))); page.set_default_timeout(30000)
                page.goto(BASE + '/oms-goods/info?goodsSn=' + c['prefix'], wait_until='networkidle')
                expect(page.locator('.goods-workspace')).to_contain_text('库存与配置')
                expect(page.locator('.goods-workspace')).to_contain_text('允许作为赠品')
                page.screenshot(path=str(OUT / 'goods-list.png'), full_page=True)
                page.goto(BASE + '/oms-goods/info?skuSn=' + c['random'], wait_until='networkidle')
                page.locator('.goods-row-actions .el-dropdown-selfdefine:visible').click()
                page.locator('.el-dropdown-menu:visible').get_by_text('修改', exact=True).click()
                dialog = page.get_by_role('dialog', name='修改商品资料', exact=True)
                expect(dialog.get_by_role('radio', name='福袋', exact=True)).to_be_checked()
                expect(dialog.get_by_role('switch')).to_be_checked()
                page.screenshot(path=str(OUT / 'goods-form.png'), full_page=True)
                dialog.get_by_role('button', name='取消', exact=True).click()
                page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(c['order_id']), wait_until='networkidle')
                drawer = page.locator('.el-drawer:visible')
                expect(drawer.locator('.order-sales-products')).to_contain_text(c['platform_oid'])
                drawer.get_by_role('tab', name='赠品', exact=True).click()
                expect(drawer.locator('.gift-contents .gift-parent')).to_have_count(5)
                expect(drawer.locator('.gift-contents')).to_contain_text('原赠 1 袋')
                page.screenshot(path=str(OUT / 'order-gifts.png'), full_page=True)
                page.set_viewport_size({'width': 1120, 'height': 1000})
                page.goto(BASE + '/oms-goods/info?goodsSn=' + c['prefix'] + '&productType=LUCKY_BAG&isGift=1', wait_until='networkidle')
                expect(page.locator('.goods-workspace .el-table__body-wrapper tbody tr')).to_have_count(2)
                page.screenshot(path=str(OUT / 'goods-filter-narrow.png'), full_page=True)
                assert not errors, errors
                browser.close()
            check('真实页面：形态筛选、独立赠品开关、库存方式、冻结组成与袋套件单位清晰')
            c['verified'] = True; save()
        if not c.get('voided'):
            o = order()
            result = api('/order/orders/business/void-batch', {'confirm_local_void': 1, 'orders': [{'id': o['id'], 'control_version': o['control_version']}], 'reason': '[验收] 完成复合赠品库存核对，释放测试占用'})
            assert result[0]['submitted'], result
            wait_for(lambda: order()['status'], lambda state: state == 'VOIDED', 'Owned order void did not complete', 150)
            c['stages']['voided'] = snapshot(); c['voided'] = True; save()
        assert c['stages']['voided'] == c['stages']['before'], c['stages']
        check('整单作废：普通赠品、两种套装和两种福袋按原来源释放，库存恢复到转单前')
        result = {'order_id': c['order_id'], 'order_sn': c['order_sn'], 'goods_prefix': c['prefix'], 'checks': checks,
                  'stages': c['stages'], 'browser_errors': errors, 'warehouse_dispatched': False, 'order_voided': True}
        (OUT / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
