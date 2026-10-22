"""Owned local TEST order: purchased and gifted kits/bags, outbound and returns.

All physical movements use inventory APIs and signed TEST WMS receipts. A durable
case file fences each stage; existing orders, scheduler switches and real WMS are
never changed. Run again to resume an interrupted acceptance.
"""
import hashlib
import io
import json
import subprocess
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

import openpyxl
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
from wms_qimen_simulator import sign
import verify_allocation_rules_v2 as fixture

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/composite-gift-refunds'
ORDER, INV, GOODS = 'qm_oms_saas_order', 'qm_oms_saas_inventory', 'qm_oms_saas_commodity'
KEYS = ['sku', 'b', 'd', 'kit', 'stocked', 'random', 'preset']
PARENTS = ['kit', 'stocked', 'random', 'preset']
PRICES = {'kit': 40, 'stocked': 30, 'random': 50, 'preset': 50}


class Acceptance:
    def __init__(self):
        OUT.mkdir(parents=True, exist_ok=True)
        self.path, self.token = OUT / 'case.json', login()
        if self.path.exists():
            self.c = json.loads(self.path.read_text('utf-8'))
        else:
            stamp = datetime.now().strftime('%Y%m%d%H%M%S')
            prefix, tid = 'RF' + stamp, '989' + stamp
            self.c = {'case_id': stamp, 'prefix': prefix, 'created': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                      **{key: prefix + suffix for key, suffix in zip(KEYS, ['A', 'B', 'D', 'K', 'S', 'R', 'P'])},
                      'seller': prefix, 'channel_name': '[套装福袋赠品退货验收] ' + stamp,
                      'platform_tid': tid, 'platform_oid': tid + '01', 'received': True,
                      'oids': {key: tid + str(i).zfill(2) for i, key in enumerate(PARENTS, 1)},
                      'warehouses': [{'code': prefix + 'V', 'quantity': 0, 'package_count': 1}],
                      'stages': {}, 'refunds': {}, 'checks': []}
            self.save()
        assert self.c['prefix'].startswith('RF') and self.c['platform_tid'].startswith('989')

    def save(self):
        self.path.write_text(json.dumps(self.c, ensure_ascii=False, indent=2), 'utf-8')

    def api(self, route, body=None, method=None):
        r = request(route, payload=body, token=self.token, method=method or ('POST' if body is not None else 'GET'))
        assert r['code'] == 200, (route, r.get('msg'))
        return r.get('data', r)

    def check(self, label):
        if label not in self.c['checks']:
            self.c['checks'].append(label)
            self.save()
        print('PASS ' + label, flush=True)

    def order(self):
        return self.api('/order/orders/business/' + str(self.c['order_id']))

    def gifts(self):
        return self.api('/order/gifts/orders/' + str(self.c['order_id']))

    def refund(self, key):
        return self.api('/order/refunds/business/' + str(self.c['refunds'][key]['id']))

    def goods(self, key):
        return self.api('/order/refunds/business/' + str(self.c['refunds'][key]['id']) + '/goods')

    def snapshot(self):
        c = self.c
        skus = ','.join(quote(c[key]) for key in KEYS)
        rows = json.loads(sql("SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('sku',sku_sn,'total',total_stock,'available',available_stock,'allocated',allocated_stock,'reserved',reserved_stock)),JSON_ARRAY()) FROM oms_inventory WHERE company_code='QM' AND sku_sn IN (" + skus + ')', INV))
        warehouse = json.loads(sql("SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('sku',sku_sn,'actual',zp_actual_number,'available',zp_available_number,'locked',zp_lock_number)),JSON_ARRAY()) FROM wms_inventory WHERE company_code='QM' AND store_code=" + quote(c['warehouses'][0]['code']) + ' AND sku_sn IN (' + skus + ')', INV))
        pools = json.loads(sql("SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('sku',bag_sku,'available',available_quantity,'occupied',occupied_quantity,'consumed',consumed_quantity)),JSON_ARRAY()) FROM oms_lucky_pool WHERE company_code='QM' AND bag_sku IN (" + quote(c['random']) + ',' + quote(c['preset']) + ')', INV))
        return {'company': sorted(rows, key=lambda x: x['sku']), 'warehouse': sorted(warehouse, key=lambda x: x['sku']),
                'pools': sorted(pools, key=lambda x: x['sku']),
                'totals': {k: sum(r[k] for r in rows) for k in ['total', 'available', 'allocated', 'reserved']}}

    def stage(self, key):
        self.c['stages'][key] = self.snapshot()
        self.save()
        return self.c['stages'][key]

    def inner(self, route, body):
        r = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-inventory', 'curl', '-fsS',
                            '-H', 'from-source: inner', '-H', 'Content-Type: application/json', '--data-binary', '@-',
                            'http://localhost:9303' + route + '?company_code=QM'], input=json.dumps(body).encode(), capture_output=True, timeout=60)
        assert r.returncode == 0, 'Local inventory API failed'
        result = json.loads(r.stdout)
        assert result['code'] == 200, result.get('msg')
        return result.get('data', result)

    def protect(self, key):
        c = self.c
        if c.get(key + '_allocation_done'):
            return
        if not c.get(key + '_allocation'):
            h = self.api('/inventory/allocation', {'ruleName': '[复合退货验收] ' + key + c['case_id'], 'ruleType': 2, 'allocationType': 1,
                         'ruleRange': 2, 'ruleMode': 2, 'stores': [c['warehouses'][0]['code']],
                         'channels': [{'channelId': c['channel_id'], 'channelName': c['channel_name'], 'ruleType': 2, 'percentage': 4, 'decimalHandleType': 1}]})
            c[key + '_allocation'] = h['id']; self.save()
            book = openpyxl.Workbook(); book.active.append(['SKU(必填)']); book.active.append([c[key]])
            stream = io.BytesIO(); book.save(stream); boundary = 'CompositeReturnFixture'
            wire = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="bags.xlsx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode() + stream.getvalue() + ('\r\n--' + boundary + '--\r\n').encode()
            req = urllib.request.Request(BASE + '/prod-api/inventory/allocation/' + str(h['id']) + '/goods?revision=' + str(h['revision']), data=wire,
                                         headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'multipart/form-data; boundary=' + boundary}, method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=40) as reply:
                imported = json.load(reply)
            assert imported['code'] == 200, imported.get('msg')
            h = self.api('/inventory/allocation/' + str(h['id']) + '/submit?revision=' + str(imported['data']['revision']), {})
            self.api('/inventory/allocation/' + str(h['id']) + '/start?revision=' + str(h['revision']) + '&action=EXECUTE', {})
        result = self.api('/inventory/allocation/' + str(c[key + '_allocation']) + '/step', {})
        assert result['status'] == 5, result
        c[key + '_allocation_done'] = True; self.save()

    def before_conversion(self, current, credential):
        c = self.c
        if c.get('fixture_ready'):
            return
        sample = self.api('/goods/info/list?pageNum=1&pageSize=1', {})['rows'][0]
        c.setdefault('goods_ids', {})
        for key in KEYS:
            kind = 'BUNDLE' if key in ['kit', 'stocked'] else 'LUCKY_BAG' if key in ['random', 'preset'] else 'NORMAL'
            found = self.api('/goods/info/list?pageNum=1&pageSize=1', {'skuSn': c[key]})['rows']
            body = {'skuSn': c[key], 'goodsSn': c['prefix'], 'goodsName': '[复合退货验收] ' + key,
                    'categoryCode': sample['categoryCode'], 'colorCode': sample['colorCode'], 'sizeCode': sample['sizeCode'],
                    'marketPrice': PRICES.get(key, 10), 'validity': 30, 'productType': kind, 'isGift': int(key in PARENTS)}
            if found:
                body['id'] = found[0]['id']; self.api('/goods/info', body, 'PUT'); ident = body['id']
            else:
                ident = self.api('/goods/info', body)
            c['goods_ids'][key] = ident
        self.save()
        for key, mode in [('kit', 'VIRTUAL'), ('stocked', 'STOCKED')]:
            if not self.api('/goods/info/' + str(c['goods_ids'][key]) + '/bundle')['revision']:
                self.api('/goods/info/' + str(c['goods_ids'][key]) + '/bundle', {'revision': 0, 'enabled': 1, 'inventory_mode': mode,
                         'components': [{'sku_sn': c['sku'], 'quantity': 2 if mode == 'VIRTUAL' else 1}, {'sku_sn': c['b'], 'quantity': 1}]})
        for key, mode in [('random', 'RANDOM'), ('preset', 'PRESET')]:
            rule = self.api('/goods/info/' + str(c['goods_ids'][key]) + '/lucky-bag')
            if not rule['revision']:
                self.api('/goods/info/' + str(c['goods_ids'][key]) + '/lucky-bag/publish', {'revision': 0, 'draft_revision': rule['draft_revision'], 'enabled': 1,
                         'mode': mode, 'selection': 'WEIGHTED', 'max_cost': 0, 'preset_quantity': 4 if mode == 'PRESET' else 0,
                         'groups': [{'key': 'top', 'name': '上装', 'candidates': [{'sku_sn': c['sku'], 'weight': 1}, {'sku_sn': c['b'], 'weight': 1}]},
                                    {'key': 'bottom', 'name': '下装', 'candidates': [{'sku_sn': c['d'], 'weight': 1}]}]})
        for key, qty in [('sku', 50), ('b', 50), ('d', 50), ('stocked', 10)]:
            if not c.get(key + '_received'):
                self.inner('/api/WmsInventory/addInventory', {'relationSn': 'RFIN' + c[key], 'wmsInventoryBatch': {
                    'companyCode': 'QM', 'storeCode': c['warehouses'][0]['code'], 'skuSn': c[key], 'batchCode': 'RF-' + c[key],
                    'zpActualNumber': qty, 'cpActualNumber': 0, 'transactionPrice': 2}})
                c[key + '_received'] = True; self.save()
        self.protect('random'); self.protect('preset')
        self.stage('before')
        if not c.get('gift_group'):
            group = self.api('/order/gifts/groups', {'name': '[复合退货验收] ' + c['case_id'], 'enabled': 1, 'policy': {
                'start_time': (datetime.now() - timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'),
                'end_time': (datetime.now() + timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'), 'shipped_policy': 'RETURN', 'allow_waive': True,
                'difference_affects_amount': False, 'rules': [{'key': 'G', 'name': '买满八套袋赠四种复合商品', 'type': 'PRODUCT',
                    'minimum': 8, 'cap': 1, 'max_count': 1, 'skus': [c[k] for k in PARENTS], 'amount': '0',
                    'gifts': [{'sku_sn': c[k], 'goods_name': '[复合退货验收] ' + k, 'quantity': 1} for k in PARENTS], 'tiers': [], 'stop': True}]}})
            c['gift_group'] = group['id']; self.save()
        if not c.get('shop_group'):
            c['shop_group'] = self.api('/order/gifts/shop-groups', {'name': '[复合退货验收] ' + c['case_id'], 'enabled': 1,
                'channels': [c['channel_id']], 'links': [{'group_id': c['gift_group'], 'continue_after_match': False}]})['id']; self.save()
        source = next(s for s in self.api('/order/orders/sources') if s['database_name'] == 'refund_acceptance_source')
        c['source_key'] = source['source_key']
        config = next(x for x in self.api('/order/orders/configs') if x['channel_id'] == c['channel_id'])
        config.update(source_key=c['source_key'], enabled=0, auto_convert=0, reset_cursor=1, initial_time=c['created'])
        self.api('/order/orders/configs', config)
        if not any(x['channel_id'] == c['channel_id'] for x in self.api('/order/refunds/configs')['configs']):
            self.api('/order/refunds/configs', {'channel_id': c['channel_id'], 'source_key': c['source_key'], 'seller_nick': c['seller'],
                'initial_time': c['created'], 'revision': 0, 'enabled': 0, 'auto_convert': 0, 'interval_seconds': 30})
        trade = {'tid': c['platform_tid'], 'status': 'WAIT_SELLER_SEND_GOODS', 'type': 'fixed', 'seller_nick': c['seller'],
                 'created': c['created'], 'modified': c['created'], 'pay_time': c['created'], 'payment': '340.00', 'post_fee': '0',
                 'receiver_name': '复合赠品退货测试', 'receiver_mobile': 'TEST-MASKED', 'receiver_state': '北京市', 'receiver_city': '北京市',
                 'receiver_district': '验收区', 'receiver_address': '验收地址，无实际配送', 'orders': {'order': [
                     {'oid': c['oids'][key], 'outer_sku_id': c[key], 'num': 2, 'payment': str(PRICES[key] * 2),
                      'divide_order_fee': str(PRICES[key] * 2), 'price': str(PRICES[key]), 'title': '[复合退货验收] 购买' + key} for key in PARENTS]}}
        payload = json.dumps({'trade_fullinfo_get_response': {'trade': trade}}, ensure_ascii=False)
        if not sql("SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id=" + str(c['channel_id']) + ' AND platform_tid=' + quote(c['platform_tid']), ORDER):
            values = [c['platform_tid'], 'WAIT_SELLER_SEND_GOODS', 'fixed', c['seller'], c['created'], c['created'], c['created'], hashlib.sha256(payload.encode()).hexdigest(), payload, 'WAITING']
            sql("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM'," + str(c['channel_id']) + ',' + ','.join(quote(x) for x in values) + ')', ORDER)
        c['fixture_ready'] = True; self.save()

    def prepare(self):
        from verify_tenant_routing_cleanup import snapshot
        if 'unrelated_before' not in self.c:
            self.c['unrelated_before'] = snapshot(); self.save()
        fixture.MANIFEST = self.path
        if not self.c.get('prepared'):
            fixture.prepare_case(self.c, self.token, approve=False, before_conversion=self.before_conversion)
            self.c['prepared'] = True; self.save()
        if not self.c.get('reserved_verified'):
            o, g = self.order(), self.gifts()
            assert len(g['parents']) == 4, g
            assert {p['sku_sn']: p['quantity'] for p in g['parents']} == {self.c[k]: 1 for k in PARENTS}
            assert len([b for b in o['bundles'] if not b.get('gift_plan_id')]) == 3
            assert len([b for b in o['bundles'] if b.get('gift_plan_id')]) == 3
            assert all(not l['platform_oid'] and l['stock_reference'].startswith('GIFT:') for l in o['lines'] if l.get('gift_plan_id'))
            snap = self.stage('reserved')
            assert snap['totals'] == {'total': 160, 'reserved': 24, 'allocated': 4, 'available': 132}, snap
            assert sum(p['available'] for p in snap['pools']) == 2 and sum(p['occupied'] for p in snap['pools']) == 6
            self.c['frozen_bundles'] = o['bundles']; self.c['original_gifts'] = g
            self.c['reserved_verified'] = True; self.save()
        self.check('同单购买和赠送两种套装、两种福袋；24个库存单位占用正确，同SKU来源分离')

    def ship(self):
        if self.c.get('shipped_verified'):
            return
        o = self.order()
        if o['review_status'] != 'APPROVED':
            assert self.api('/order/orders/business/approve-batch', {'orders': [{'id': o['id'], 'control_version': o['control_version']}], 'reason': '[验收] 复合购买与赠品发货'})[0]['success']
        o = self.order()
        if o['allocation_status'] != 'FULL':
            result = self.api('/order/orders/business/allocate-batch', {'items': [{'id': o['id'], 'control_version': o['control_version']}]})
            assert result[0]['success'], result
        self.stage('allocated')
        for shipment in self.order()['shipments']:
            s = self.shipment(shipment['id'])
            if s['carrier_match_status'] != 'MATCHED':
                result = self.api('/order/orders/shipments/match-carrier', {'items': [{'id': s['id'], 'control_version': s['control_version']}]})
                assert result[0]['success'], result
                s = self.shipment(s['id'])
            if s['wms_dispatch_state'] == 'NONE':
                result = self.api('/order/orders/shipments/dispatch', {'items': [{'id': s['id'], 'control_version': s['control_version']}]})
                assert result[0]['success'], result
            s = self.shipment(s['id'])
            if s['wms_dispatch_state'] == 'PENDING':
                self.api('/order/orders/shipments/' + str(s['id']) + '/continue-dispatch', {'control_version': s['control_version']})
            s = wait_for(lambda: self.shipment(s['id']), lambda x: x['wms_dispatch_state'] == 'ACCEPTED', 'TEST dispatch incomplete')
            assert s['warehouse_target']['environment'] == 'TEST'
            if s['shipped_quantity'] != s['quantity']:
                r = subprocess.run([sys.executable, str(Path(__file__).with_name('simulate_qimen_delivery.py')), s['shipment_sn']],
                                   capture_output=True, text=True, encoding='utf-8', timeout=150)
                assert r.returncode == 0, 'TEST outbound failed: ' + r.stdout[-350:] + r.stderr[-350:]
            wait_for(lambda: self.shipment(s['id']), lambda x: x['shipped_quantity'] == x['quantity'], 'TEST outbound incomplete')
        o = self.order()
        assert o['shipped_quantity'] == 24
        snap = self.stage('shipped')
        assert snap['totals'] == {'total': 136, 'available': 132, 'allocated': 4, 'reserved': 0}, snap
        assert sum(w['actual'] for w in snap['warehouse']) == 136 and sum(w['locked'] for w in snap['warehouse']) == 4
        assert sum(p['consumed'] for p in snap['pools']) == 6
        self.c['shipped_order'] = o; self.c['shipped_verified'] = True; self.save()
        self.check('TEST WMS真实发货及重复回传：只扣实际组件和成品套装，福袋父SKU不扣虚拟库存')

    def shipment(self, ident):
        return self.api('/order/orders/shipments/' + str(ident))

    def create_refund(self, key, index):
        c = self.c
        if key not in c['refunds']:
            c['refunds'][key] = {'number': '988' + c['case_id'] + str(index).zfill(2)}; self.save()
        r = c['refunds'][key]
        if r.get('id'):
            return
        before = self.snapshot()
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        raw = {'refund_id': r['number'], 'tid': c['platform_tid'], 'oid': c['oids'][key], 'seller_nick': c['seller'], 'status': 'SUCCESS',
               'refund_fee': str(PRICES[key]), 'num': 1, 'has_good_return': True, 'dispute_type': 'REFUND_AND_RETURN',
               'refund_phase': 'aftersale', 'refund_version': '1', 'reason': '[复合验收] 退一套或一原袋', 'created': now, 'modified': now}
        values = [r['number'], c['platform_tid'], c['oids'][key], raw['status'], c['seller'], now, now, now, json.dumps({'refund_get_response': {'refund': raw}}, ensure_ascii=False)]
        sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES (' + ','.join(quote(v) for v in values) + ')', 'refund_acceptance_source')
        task = self.api('/order/refunds/sync', {'channel_id': c['channel_id'], 'mode': 'IDS', 'refund_ids': [r['number']], 'request_key': 'RF_SYNC_' + r['number']})
        wait_for(lambda: self.api('/order/refunds/tasks/' + str(task['id'])), lambda t: t['state'] == 'COMPLETED', 'TEST refund intake incomplete', 150)
        p = self.api('/order/refunds/platform?number=' + r['number'])['rows'][0]
        if not p.get('business_refund_id'):
            result = self.api('/order/refunds/platform/' + str(p['id']) + '/convert', {})
            assert result['state'] in ['SUCCESS', 'PENDING'], result
        p = wait_for(lambda: self.api('/order/refunds/platform/' + str(p['id'])), lambda x: bool(x.get('business_refund_id')), 'TEST refund conversion incomplete', 150)
        r['id'] = p['business_refund_id']; self.save()
        b = self.refund(key)
        if b['review_state'] != 'APPROVED':
            self.api('/order/refunds/business/' + str(r['id']) + '/review', {'control_version': b['control_version'], 'platform_version': b['platform']['version'],
                'purpose': 'CANCEL', 'goods_action': 'RETURN', 'reason': '[复合验收] 原出库退回，不改组成', 'evidence': 'TEST-' + c['prefix']})
        r['sn'] = self.refund(key)['refund_sn']; self.save()
        assert self.snapshot() == before, 'Refund approval changed physical stock'

    def return_body(self, key):
        return {'control_version': self.refund(key)['control_version'], 'quantity': 1, 'store_code': self.c['warehouses'][0]['code'],
                'return_address': '[复合退货验收] 原仓退回，无实际配送', 'return_contact': 'TEST-COMPOSITE', 'reason': '[验收] 原来源实物退回',
                'evidence': 'TEST-' + self.c['prefix'], 'confirmed': True}

    def plan_purchase(self, key):
        r = self.c['refunds'][key]
        if r.get('purchase_planned'):
            return
        before = self.snapshot()
        body = self.return_body(key)
        if key == 'stocked':
            line = next(l for l in self.order()['lines'] if l['platform_oid'] == self.c['oids'][key] and not l.get('gift_plan_id'))
            source = next(l for s in self.c['shipped_order']['shipments'] for l in self.shipment(s['id'])['lines'] if l['order_line_id'] == line['id'])
            body.update(shipment_line_id=source['id'], request_key='RF_PURCHASE_' + r['number'])
            self.api('/order/refunds/business/' + str(r['id']) + '/goods/plans', body)
        else:
            bundle = next(b for b in self.order()['bundles'] if b['platform_oid'] == self.c['oids'][key] and not b.get('gift_plan_id'))
            body['bundle_id'] = bundle['id']
            if key in ['random', 'preset']:
                body['unit_ids'] = [bundle['units'][0]['unit_id']]
                r['returned_unit'] = bundle['units'][0]; self.save()
            self.api('/order/refunds/business/' + str(r['id']) + '/goods/bundle-plans', body)
        assert self.snapshot() == before, 'Planning purchased returns changed stock'
        r['purchase_planned'] = True; self.save()

    def plan_gifts(self, key):
        r = self.c['refunds'][key]
        if r.get('gifts_planned'):
            return
        before, g = self.snapshot(), self.gifts()
        assert all(p['eligible_quantity'] == 0 for p in g['parents']), g
        for line in g['lines']:
            for source in line.get('sources', []):
                used = sum(p['quantity'] for p in self.goods(key)['plans'] if p.get('gift_plan_id') and p['shipment_line_id'] == source['id'])
                qty = int(source['shipped_quantity'])
                if used:
                    assert used == qty
                    continue
                body = self.return_body(key)
                body.update(gift_plan_id=g['id'], shipment_line_id=source['id'], quantity=qty, request_key='RF_GIFT_' + str(source['id']) + '_' + r['number'])
                self.api('/order/gifts/refunds/' + str(r['id']) + '/return', body)
        plans = self.goods(key)['plans']
        assert sum(p['quantity'] for p in plans if p.get('gift_plan_id')) == 8
        assert len({p['shipment_line_id'] for p in plans}) == len(plans)
        assert self.snapshot() == before, 'Planning gift returns changed stock'
        r['gifts_planned'] = True; self.save()

    def action(self, key, ident, action, doc=0):
        p = next(p for p in self.goods(key)['plans'] if p['id'] == ident)
        body = {'control_version': self.refund(key)['control_version'], 'plan_version': p['control_version']}
        if doc:
            body['document_version'] = next(d['control_version'] for d in p['actual']['documents'] if d['id'] == doc)
        route = '/order/gifts/refunds/' if p.get('gift_plan_id') else '/order/refunds/business/'
        route += str(self.c['refunds'][key]['id']) + ('/plans/' if p.get('gift_plan_id') else '/goods/plans/') + str(ident) + '/documents/' + str(doc) + '/' + action
        return self.api(route, body)

    def receive_plan(self, key, ident):
        c = self.c
        p = next(p for p in self.goods(key)['plans'] if p['id'] == ident)
        if p['posted_quantity'] == p['quantity']:
            return
        if not p['actual']:
            before = self.snapshot(); self.action(key, ident, 'dispatch')
            self.api('/order/refunds/business/' + str(c['refunds'][key]['id']) + '/goods/refresh', {})
            assert self.snapshot() == before, 'Dispatching returns changed stock'
        p = next(p for p in self.goods(key)['plans'] if p['id'] == ident)
        a, doc = p['actual'], p['actual']['documents'][0]
        assert doc['dispatch_state'] == 'ACCEPTED' and doc['quantity'] == p['quantity'], p
        connection = next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id'] == c['connection_id'])
        assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
        credential = next(x for x in json.loads((ROOT / 'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key'] == connection['app_key'] and x['customer_id'] == connection['customer_id'])
        root = ET.Element('request'); head = ET.SubElement(root, 'returnOrder')
        for k, v in {'returnOrderCode': doc['return_sn'], 'returnOrderId': doc['external_order'], 'warehouseCode': a['external_warehouse'], 'ownerCode': a['external_owner'],
                     'outBizCode': 'RF_RETURN_' + str(ident), 'orderType': 'THRK', 'confirmType': '0'}.items():
            ET.SubElement(head, k).text = str(v)
        line = ET.SubElement(ET.SubElement(root, 'orderLines'), 'orderLine')
        for k, v in {'itemCode': p['sku_sn'], 'ownerCode': a['external_owner'], 'actualQty': p['quantity'], 'inventoryType': 'ZP', 'batchCode': 'RF_RETURN_' + str(ident)}.items():
            ET.SubElement(line, k).text = str(v)
        wire = ET.tostring(root, encoding='unicode')
        (OUT / ('return-' + str(ident) + '.xml')).write_text(wire, 'utf-8')
        marker = {'customer': connection['customer_id'], 'owner': a['external_owner'], 'warehouse': a['external_warehouse'], 'sn': doc['return_sn'], 'external': doc['external_order'], 'quantity': p['quantity']}
        script = "import json,sqlite3\np=json.loads(" + repr(json.dumps(marker)) + ")\nwith sqlite3.connect('/data/documents.sqlite',timeout=10) as db:\n row=db.execute(\"SELECT external_id,quantity,status FROM documents WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?\",tuple(p[k] for k in ['customer','owner','warehouse','sn'])).fetchone()\n assert row and row[0]==p['external'] and row[1]==p['quantity'] and row[2] in ('ACCEPT','FULFILLED')\n db.execute(\"UPDATE documents SET status='FULFILLED' WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?\",tuple(p[k] for k in ['customer','owner','warehouse','sn']))\n"
        marked = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-wms-simulator', 'python', '-'], input=script.encode(), capture_output=True, timeout=30)
        assert marked.returncode == 0, 'TEST document identity mismatch'
        before = self.snapshot()
        for replay in range(2):
            previous = self.snapshot()
            params = {'app_key': connection['app_key'], 'customerId': connection['customer_id'], 'method': 'taobao.qimen.returnorder.confirm', 'v': '2.0', 'format': 'xml', 'sign_method': 'md5', 'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
            params['sign'] = sign(params, wire, credential['secret'])
            req = urllib.request.Request(BASE + '/prod-api/supplychain/wmsCallback/qm/' + connection['callback_key'] + '?' + urllib.parse.urlencode(params), data=wire.encode(), headers={'Content-Type': 'application/xml;charset=UTF-8'}, method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=50) as response:
                reply = ET.fromstring(response.read())
            assert reply.findtext('flag') == 'success', reply.findtext('message')
            self.api('/order/refunds/business/' + str(c['refunds'][key]['id']) + '/goods/refresh', {})
            self.action(key, ident, 'posting', doc['id'])
            if replay:
                assert self.snapshot() == previous, 'Duplicate receipt/posting increased stock'
        after = self.snapshot()
        assert after['totals']['total'] == before['totals']['total'] + p['quantity'], (before, after, p)
        assert next(p for p in self.goods(key)['plans'] if p['id'] == ident)['posted_quantity'] == p['quantity']

    def returns(self):
        for index, key in enumerate(['kit', 'random', 'stocked', 'preset'], 1):
            r = self.c['refunds'].get(key, {})
            if r.get('verified'):
                continue
            self.create_refund(key, index); self.plan_purchase(key)
            if index == 1:
                self.plan_gifts(key)
                self.stage('first_return_planned')
                self.check('首次退一套后购买门槛失效，四种赠品均追退，购买退回与赠品退回来源分开')
            else:
                assert not any(p.get('gift_plan_id') for p in self.goods(key)['plans'])
                assert all(l['unarranged_quantity'] == 0 for l in self.gifts()['lines'])
            expected = 11 if key == 'kit' else 1 if key == 'stocked' else 2
            assert sum(p['quantity'] for p in self.goods(key)['plans']) == expected
            for p in self.goods(key)['plans']:
                self.receive_plan(key, p['id'])
            assert self.refund(key)['funds_state'] == 'SUCCESS'
            assert sum(p['posted_quantity'] for p in self.goods(key)['plans']) == expected
            self.stage('returned_' + key)
            self.c['refunds'][key]['verified'] = True; self.save()
            self.check(key + '退款：原来源退回入库、重复回传与重复记账不多加库存')

    def finish(self):
        from verify_tenant_routing_cleanup import snapshot
        self.verify_lineage()
        self.verify_rejections()
        for key in PARENTS:
            b = self.refund(key)
            if b['goods_state'] != 'DONE':
                before = self.snapshot()
                self.api('/order/refunds/business/' + str(b['id']) + '/goods/finish', {
                    'control_version': b['control_version'], 'confirmed': True, 'reason': '[验收] 原来源数量、仓库实收和入账已逐项核对',
                    'evidence': 'TEST-' + self.c['prefix']})
                assert self.snapshot() == before, 'Quality completion changed inventory'
            assert self.refund(key)['goods_state'] == 'DONE'
        assert snapshot() == self.c['unrelated_before'], 'Existing retained orders or jobs changed'
        snap, g = self.stage('final'), self.gifts()
        assert snap['totals'] == {'total': 152, 'available': 148, 'allocated': 4, 'reserved': 0}, snap
        assert sum(w['actual'] for w in snap['warehouse']) == 152 and sum(w['locked'] for w in snap['warehouse']) == 4
        assert sum(p['available'] for p in snap['pools']) == 2 and sum(p['consumed'] for p in snap['pools']) == 6
        assert all(l['return_required'] == 0 and l['unarranged_quantity'] == 0 for l in g['lines']), g
        assert sum(p['quantity'] for p in self.goods('kit')['plans'] if p.get('gift_plan_id')) == 8
        assert sum(p['posted_quantity'] for p in self.goods('kit')['plans'] if p.get('gift_plan_id')) == 8
        assert all(not any(p.get('gift_plan_id') for p in self.goods(k)['plans']) for k in ['random', 'stocked', 'preset'])
        self.check('四笔退款无重复追赠；库存恢复16个单位，福袋实际组件回库且未重建售出袋配额')
        result = {'order_id': self.c['order_id'], 'order_sn': self.c['order_sn'], 'platform_tid': self.c['platform_tid'],
                  'order_url': BASE + '/oms-order/business?view=business&orderId=' + str(self.c['order_id']),
                  'refunds': self.c['refunds'], 'checks': self.c['checks'], 'stages': self.c['stages'], 'gift_final': g,
                  'sku_reconciliation': self.c['sku_reconciliation'], 'return_lineage': self.c['return_lineage'],
                  'real_platform_writes': False, 'warehouse_environment': 'TEST'}
        self.verify_ui()
        result['checks'] = self.c['checks']
        (OUT / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        print(json.dumps({'order_id': self.c['order_id'], 'order_sn': self.c['order_sn'], 'refund_ids': [self.c['refunds'][k]['id'] for k in PARENTS],
                          'final_stock': snap['totals'], 'report': str(OUT / 'result.json')}, ensure_ascii=False), flush=True)

    def verify_lineage(self):
        c, outgoing, returned = self.c, Counter(), Counter()
        order = c['shipped_order']
        lines = {line['id']: line for line in order['lines']}
        sources = {line['id']: line for s in order['shipments'] for line in self.shipment(s['id'])['lines']}
        for source in sources.values():
            outgoing[source['sku_sn']] += source['shipped_quantity']
        lineage = []
        for key in PARENTS:
            goods = self.goods(key)
            paid = Counter()
            assert self.refund(key)['refund_fee'] == PRICES[key]
            for p in goods['plans']:
                source = sources[p['shipment_line_id']]
                line = lines[source['order_line_id']]
                assert line['sku_sn'] == p['sku_sn']
                assert bool(p.get('gift_plan_id')) == bool(line.get('gift_plan_id'))
                assert p['quantity'] == p['accepted_quantity'] == p['posted_quantity']
                if p.get('gift_plan_id'):
                    assert key == 'kit' and line['stock_reference'].startswith('GIFT:') and not line['platform_oid']
                else:
                    assert line['platform_oid'] == c['oids'][key]
                    paid[p['sku_sn']] += p['quantity']
                returned[p['sku_sn']] += p['posted_quantity']
                lineage.append({'refund_id': c['refunds'][key]['id'], 'plan_id': p['id'], 'purchase_or_gift': 'GIFT' if p.get('gift_plan_id') else 'PURCHASE',
                                'sku': p['sku_sn'], 'quantity': p['quantity'], 'shipment_line_id': source['id'], 'order_line_id': line['id'],
                                'stock_reference': line['stock_reference'], 'platform_oid': line['platform_oid'], 'stock_source_id': source.get('stock_source_id'),
                                'return_documents': [d['return_sn'] for d in p['actual']['documents']]})
            if key in ['random', 'preset']:
                unit = c['refunds'][key]['returned_unit']
                expected = Counter({p['sku_sn']: p['quantity'] for p in unit['components']})
                assert paid == expected, (key, paid, expected)
                assert goods['bundle_returns'][0]['unit_ids'] == [unit['unit_id']]
                paid_sources = [sources[p['shipment_line_id']] for p in goods['plans'] if not p.get('gift_plan_id')]
                assert {p['stock_source_id'] for p in paid_sources} == {p['stock_source_id'] for p in unit['components']}
            elif key == 'kit':
                assert paid == Counter({c['sku']: 2, c['b']: 1})
            else:
                assert paid == Counter({c['stocked']: 1})
        initial = {r['sku']: r['total'] for r in c['stages']['before']['company']}
        final = {r['sku']: r['total'] for r in self.snapshot()['company']}
        warehouse = {r['sku']: r['actual'] for r in self.snapshot()['warehouse']}
        rows = []
        for key in KEYS:
            sku = c[key]; expected = initial.get(sku, 0) - outgoing[sku] + returned[sku]
            assert final.get(sku, 0) == warehouse.get(sku, 0) == expected, (sku, expected, final, warehouse)
            rows.append({'sku': sku, 'key': key, 'unit': '套' if key == 'stocked' else '件', 'initial': initial.get(sku, 0),
                         'shipped': outgoing[sku], 'returned': returned[sku], 'expected': expected, 'actual': final.get(sku, 0)})
        assert sum(outgoing.values()) == 24 and sum(returned.values()) == 16
        c['sku_reconciliation'], c['return_lineage'] = rows, lineage
        self.save()
        self.check('逐SKU公司账和仓库账一致；付费原袋ID及出库库存来源准确，成品套装按整套回库')

    def verify_rejections(self):
        if self.c.get('rejections_verified'):
            return
        plans, g = self.goods('kit')['plans'], self.gifts()
        purchase = next(p for p in plans if not p.get('gift_plan_id'))
        gift = next(p for p in plans if p.get('gift_plan_id'))
        attempts = [
            ('购买商品不能从赠品入口退回', '/order/gifts/refunds/' + str(self.c['refunds']['kit']['id']) + '/return',
             {**self.return_body('kit'), 'shipment_line_id': purchase['shipment_line_id'], 'gift_plan_id': g['id'], 'request_key': 'RF_WRONG_GIFT_' + self.c['case_id']}, '正品或赠品退回入口'),
            ('赠品不能从购买商品入口退回', '/order/refunds/business/' + str(self.c['refunds']['kit']['id']) + '/goods/plans',
             {**self.return_body('kit'), 'shipment_line_id': gift['shipment_line_id'], 'request_key': 'RF_WRONG_PAID_' + self.c['case_id']}, '正品或赠品退回入口'),
            ('后续退款不能重复追回已退赠品', '/order/gifts/refunds/' + str(self.c['refunds']['random']['id']) + '/return',
             {**self.return_body('random'), 'shipment_line_id': gift['shipment_line_id'], 'gift_plan_id': g['id'], 'request_key': 'RF_DUPLICATE_' + self.c['case_id']}, '已安排或已豁免')]
        for label, route, body, message in attempts:
            before = self.snapshot(); error = None
            try:
                request(route, payload=body, token=self.token, method='POST')
            except AssertionError as e:
                error = str(e)
            assert error and message in error, (label, error)
            assert self.snapshot() == before, label + '改变了库存'
            self.check(label + '，错误请求已拦截且库存不变')
        self.c['rejections_verified'] = True; self.save()

    def verify_ui(self):
        from playwright.sync_api import sync_playwright, expect
        errors, before = [], self.snapshot()
        purchase = self.order()['purchase_returns']
        assert len(purchase) == 7 and sum(p['posted_quantity'] for p in purchase) == 8
        assert {p['id'] for p in purchase} == {p['id'] for key in PARENTS for p in self.goods(key)['plans'] if not p.get('gift_plan_id')}
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': self.token, 'url': BASE}])
            page = context.new_page(); page.set_default_timeout(30000)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(self.c['order_id']), wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            drawer.get_by_role('tab', name='商品明细', exact=True).click()
            expect(drawer).to_contain_text(self.c['oids']['kit'])
            expect(drawer).to_contain_text(self.c['oids']['random'])
            table = drawer.locator('.sales-products-table .el-table__body-wrapper').first
            for key, text in [('kit', '已入库 3 件'), ('stocked', '已入库 1 套'), ('random', '已入库 2 件'), ('preset', '已入库 2 件')]:
                expect(table.locator('tbody > tr').filter(has_text=self.c[key])).to_contain_text(text)
            page.screenshot(path=str(OUT / 'order-purchases.png'), full_page=True)
            drawer.get_by_role('tab', name='赠品', exact=True).click()
            expect(drawer.locator('.gift-parent')).to_have_count(4)
            page.screenshot(path=str(OUT / 'order-gifts.png'), full_page=True)
            for key in ['kit', 'random']:
                page.goto(BASE + '/oms-order/businessRefunds?id=' + str(self.c['refunds'][key]['id']), wait_until='networkidle')
                dialog = page.get_by_role('dialog', name='业务退款详情 · 退货退款', exact=True)
                expect(dialog).to_contain_text(self.c['refunds'][key]['sn'])
                bound = dialog.locator('.refund-bound-gifts')
                if key == 'kit':
                    expect(bound).to_contain_text('本退款关联赠品')
                    expect(bound.locator('.el-table__body-wrapper').first.locator('tbody > tr')).to_have_count(7)
                else:
                    expect(bound).to_contain_text('本退款暂无关联赠品')
                bound.scroll_into_view_if_needed()
                page.screenshot(path=str(OUT / ('refund-' + key + '-gifts.png')), full_page=True)
            assert not errors, errors
            browser.close()
        assert self.snapshot() == before, 'Read-only browser checks changed stock'
        (OUT / 'ui.json').write_text(json.dumps({'browser_errors': errors, 'read_only_stock_unchanged': True}, indent=2), 'utf-8')
        self.check('浏览器验收：购买商品与赠品分开，首次退款关联赠品、后续退款无重复关联')

    def run(self):
        try:
            self.prepare(); self.ship(); self.returns(); self.finish()
        finally:
            request('/auth/logout', token=self.token, method='DELETE')


if __name__ == '__main__':
    Acceptance().run()
