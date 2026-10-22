"""One owned TEST XS, then one shared return parcel/THK across four refunds.

Purchased and gifted composites share physical documents while source identities
and refund/gift ownership remain separate. No real platform or WMS calls.
"""
import json
import subprocess
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime
from pathlib import Path

import verify_composite_gift_refunds as original
from verify_goods_workspace_ui import BASE
from wms_qimen_simulator import sign

OUT = original.ROOT / 'outputs/merged-composite-gift-refunds'
original.OUT = OUT


class Acceptance(original.Acceptance):
    def protected_order(self):
        previous = json.loads((original.ROOT / 'outputs/composite-gift-refunds/case.json').read_text('utf-8'))
        scope = ','.join(original.quote(previous[k]) for k in original.KEYS)
        return original.sql("SELECT sku_sn,total_stock,reserved_stock,allocated_stock,available_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn IN (" + scope + ') ORDER BY sku_sn', original.INV)

    def prepare(self):
        if 'preceding_order_stock' not in self.c:
            self.c['preceding_order_stock'] = self.protected_order(); self.save()
        super().prepare()

    def before_conversion(self, current, credential):
        super().before_conversion(current, credential)
        c = self.c
        if c.get('shared_return_ready'):
            return
        connection = next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id'] == c['connection_id'])
        assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
        secret = next(x for x in json.loads((original.ROOT / 'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key'] == connection['app_key'] and x['customer_id'] == connection['customer_id'])
        if not c.get('parcel_connection'):
            body = {k: connection[k] for k in ['provider', 'api_version', 'environment', 'endpoint', 'app_key', 'customer_id']}
            body.update(name='[同发合退验收] ' + c['case_id'], secret=secret['secret'], enabled=1, shared_return_waybill=1)
            c['parcel_connection'] = self.api('/supplychain/wmsIntegration/connections', body); self.save()
        w = self.api('/supplychain/simulationStore/' + str(c['warehouses'][0]['id']))
        if w['connectionId'] != c['parcel_connection']:
            self.api('/supplychain/simulationStore', {**{k: w[k] for k in ['id', 'wmsSimulationCode', 'wmsSimulationName', 'ownerWarehouseId', 'inboundMode', 'outboundMode', 'externalWarehouse', 'externalOwner', 'status']}, 'connectionId': c['parcel_connection']}, 'PUT')
        c['connection_id'] = c['parcel_connection']
        config = self.api('/order/orders/allocation-config/' + str(c['channel_id']))
        self.api('/order/orders/allocation-config', {'channel_id': c['channel_id'], 'revision': config['revision'], 'policy': {**config['policy'], 'max_shipments': 1}})
        config = self.api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']
        self.api('/order/refunds/parcel-configs/' + str(c['channel_id']), {**config, 'merge_mode': 'MANUAL', 'auto_create': 0, 'auto_dispatch': 0,
            'auto_allocate': 0, 'allocation_mode': 'MANUAL', 'store_codes': [c['warehouses'][0]['code']]})
        c['shared_return_ready'] = True; self.save()

    def ship(self):
        c = self.c
        if not c.get('shipped_verified') and self.order()['allocation_status'] != 'FULL':
            o = self.order()
            if o['review_status'] != 'APPROVED':
                assert self.api('/order/orders/business/approve-batch', {'orders': [{'id': o['id'], 'control_version': o['control_version']}], 'reason': '[验收] 同仓合并购买商品与赠品'})[0]['success']
            o = self.order(); before = self.snapshot()
            p = self.api('/order/orders/business/allocation-preview', {'items': [{'id': o['id'], 'control_version': o['control_version']}]})[0]
            assert p['can_allocate'] and p['new_shipment_count'] == 1 and p['planned_quantity'] == 24, p
            assert self.snapshot() == before
            c['allocation_preview'] = p; self.save()
            r = self.api('/order/orders/business/allocate-batch', {'items': [{k: p[k] for k in ['id', 'control_version', 'policy_fingerprint', 'plan_fingerprint']}]})[0]
            assert r['success'], r
            assert len(self.order()['shipments']) == 1
        super().ship()
        shipments = self.order()['shipments']
        assert len(shipments) == 1 and shipments[0]['shipped_quantity'] == 24
        assert self.shipment(shipments[0]['id'])['package_count'] == 1
        c['shipment_sn'] = shipments[0]['shipment_sn']; self.save()
        self.check('预览及确认均为1张XS，发货单上限1生效；购买商品和四种赠品同单出库')

    def parcels(self):
        return self.api('/order/refunds/business/' + str(self.c['refunds']['kit']['id']) + '/parcels')

    def returns(self):
        c = self.c
        if c.get('shared_return_verified'):
            return
        if not c.get('all_return_plans_ready'):
            for index, key in enumerate(['kit', 'random', 'stocked', 'preset'], 1):
                self.create_refund(key, index); self.plan_purchase(key)
                if index == 1:
                    self.plan_gifts(key)
                else:
                    assert not any(p.get('gift_plan_id') for p in self.goods(key)['plans'])
            assert self.snapshot() == c['stages']['shipped'], 'Refunds/planning changed inventory'
            c['all_return_plans_ready'] = True; self.stage('all_returns_planned')
        for key in original.PARENTS:
            for p in self.goods(key)['plans']:
                if p.get('parcel_state') == 'LINKED':
                    continue
                body = {'control_version': self.refund(key)['control_version'], 'plan_version': p['control_version'], 'carrier_code': 'SF',
                        'waybill': 'TEST_MERGED_' + c['case_id'], 'confirmed': True, 'reason': '[验收] 购买商品及赠品同一实际包裹寄回', 'evidence': 'TEST-' + c['prefix']}
                if c.get('parcel_id'):
                    body['join_parcel_id'] = c['parcel_id']
                parcel = self.api('/order/refunds/business/' + str(c['refunds'][key]['id']) + '/parcels/plans/' + str(p['id']), body)
                c.update(parcel_id=parcel['id'], parcel_sn=parcel['parcel_sn']); self.save()
        parcel = self.parcels()[0]
        assert len(self.parcels()) == 1 and len(parcel['members']) == 14
        assert len({m['refund_id'] for m in parcel['members']}) == 4
        assert sum(m['quantity'] for m in parcel['members']) == 16
        if not parcel['warehouse_document']:
            self.api('/order/refunds/business/' + str(c['refunds']['kit']['id']) + '/parcels/' + str(c['parcel_id']) + '/document/dispatch', {
                'parcel_version': parcel['control_version'], 'confirmed_shared': True, 'reason': '[验收] 同一运单统一创建一张THK', 'evidence': 'TEST-' + c['prefix']})
        for key in original.PARENTS:
            self.api('/order/refunds/business/' + str(c['refunds'][key]['id']) + '/goods/refresh', {})
        parcel = self.parcels()[0]; doc = parcel['warehouse_document']
        assert doc['dispatch_state'] == 'ACCEPTED' and doc['quantity'] == 16
        names = {p['actual']['return_sn'] for key in original.PARENTS for p in self.goods(key)['plans']}
        assert names == {doc['return_sn']}, names
        c['return_sn'] = doc['return_sn']; self.save()
        assert self.snapshot() == c['stages']['shipped'], 'Attaching/dispatching parcel changed inventory'
        self.check('4张退款、14条退回来源合为1个退回包裹及1张THK，购买与赠品分别归属')
        self.receive_shared(parcel)
        for key in original.PARENTS:
            plans = self.goods(key)['plans']
            assert all(p['posted_quantity'] == p['accepted_quantity'] == p['quantity'] for p in plans)
            c['refunds'][key]['verified'] = True
        c['shared_return_verified'] = True; self.stage('shared_return_posted')

    def receive_shared(self, parcel):
        c = self.c; doc = parcel['warehouse_document']
        connection = next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id'] == c['connection_id'])
        assert connection['environment'] == 'TEST' and connection['shared_return_waybill'] == 1
        credential = next(x for x in json.loads((original.ROOT / 'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key'] == connection['app_key'] and x['customer_id'] == connection['customer_id'])
        plans = {p['id']: p for key in original.PARENTS for p in self.goods(key)['plans']}
        actual = next(iter(plans.values()))['actual']
        quantities = Counter()
        for p in plans.values():
            quantities[p['sku_sn']] += p['quantity']
        skus = sorted(quantities)
        root = ET.Element('request'); head = ET.SubElement(root, 'returnOrder')
        for key, value in {'returnOrderCode': doc['return_sn'], 'returnOrderId': doc['external_order'], 'warehouseCode': actual['external_warehouse'],
                           'ownerCode': actual['external_owner'], 'outBizCode': 'MERGED_RETURN_' + c['case_id'], 'orderType': 'THRK', 'confirmType': '0'}.items():
            ET.SubElement(head, key).text = str(value)
        rows = ET.SubElement(root, 'orderLines')
        for index, sku in enumerate(skus):
            line = ET.SubElement(rows, 'orderLine')
            for key, value in {'itemCode': sku, 'ownerCode': actual['external_owner'], 'actualQty': quantities[sku], 'inventoryType': 'ZP', 'batchCode': 'MERGED_RETURN_' + c['case_id'] + '_' + str(index)}.items():
                ET.SubElement(line, key).text = str(value)
        wire = ET.tostring(root, encoding='unicode'); (OUT / 'shared-return.xml').write_text(wire, 'utf-8')
        marker = {'customer': connection['customer_id'], 'owner': actual['external_owner'], 'warehouse': actual['external_warehouse'], 'sn': doc['return_sn'], 'external': doc['external_order']}
        script = "import json,sqlite3\np=json.loads(" + repr(json.dumps(marker)) + ")\nwith sqlite3.connect('/data/documents.sqlite',timeout=10) as db:\n row=db.execute(\"SELECT external_id,quantity,status FROM documents WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?\",tuple(p[k] for k in ['customer','owner','warehouse','sn'])).fetchone()\n assert row and row[0]==p['external'] and row[1]==16 and row[2] in ('ACCEPT','FULFILLED')\n db.execute(\"UPDATE documents SET status='FULFILLED' WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?\",tuple(p[k] for k in ['customer','owner','warehouse','sn']))\n"
        marked = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-wms-simulator', 'python', '-'], input=script.encode(), capture_output=True, timeout=30)
        assert marked.returncode == 0, 'TEST shared THK identity mismatch'

        def callback():
            params = {'app_key': connection['app_key'], 'customerId': connection['customer_id'], 'method': 'taobao.qimen.returnorder.confirm', 'v': '2.0', 'format': 'xml', 'sign_method': 'md5', 'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
            params['sign'] = sign(params, wire, credential['secret'])
            req = urllib.request.Request(BASE + '/prod-api/supplychain/wmsCallback/qm/' + connection['callback_key'] + '?' + urllib.parse.urlencode(params), data=wire.encode(), headers={'Content-Type': 'application/xml;charset=UTF-8'}, method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=60) as response:
                reply = ET.fromstring(response.read())
            assert reply.findtext('flag') == 'success', reply.findtext('message')

        callback(); callback()
        for key in original.PARENTS:
            self.api('/order/refunds/business/' + str(c['refunds'][key]['id']) + '/goods/refresh', {})
        parcel = self.parcels()[0]
        assert len(parcel['receipts']) == 1 and parcel['receipts'][0]['quantity'] == 16
        event = parcel['receipts'][0]
        if event['state'] == 'WAIT_ALLOCATION':
            assert self.snapshot() == c['stages']['shipped'], 'Unassigned shared receipt changed inventory'
            allocations = [{'line_index': skus.index(plans[m['plan_id']]['sku_sn']), 'task_id': m['task_id'], 'good_qty': m['quantity'], 'bad_qty': 0} for m in parcel['members']]
            self.api('/order/refunds/business/' + str(c['refunds']['kit']['id']) + '/parcels/' + str(c['parcel_id']) + '/receipts/' + str(event['id']), {
                'confirmed': True, 'decision': 'ALLOCATE', 'allocations': allocations, 'reason': '[验收] 核实各退款及赠品原来源，全量实收归属确认', 'evidence': 'TEST-' + c['prefix']})
        for key in original.PARENTS:
            self.api('/order/refunds/business/' + str(c['refunds'][key]['id']) + '/goods/refresh', {})
        before_replay = self.snapshot(); callback()
        parcel = self.parcels()[0]
        self.api('/order/refunds/business/' + str(c['refunds']['kit']['id']) + '/parcels/' + str(c['parcel_id']) + '/document/posting', {
            'parcel_version': parcel['control_version'], 'confirmed_shared': True, 'reason': '[验收] 重复核对同一合包实收', 'evidence': 'TEST-' + c['prefix']})
        for key in original.PARENTS:
            self.api('/order/refunds/business/' + str(c['refunds'][key]['id']) + '/goods/refresh', {})
        assert self.snapshot() == before_replay
        assert before_replay['totals']['total'] == 152
        self.check('合包回传按购买/赠品原来源分配，重复回传及记账仅入库一次，未分配实收不加库存')

    def finish(self):
        super().finish()
        assert self.protected_order() == self.c['preceding_order_stock'], 'Previous shipped acceptance stock changed'
        path = OUT / 'result.json'; result = json.loads(path.read_text('utf-8'))
        result.update(shipment_count=1, shipment_sn=self.c['shipment_sn'], parcel_id=self.c['parcel_id'], parcel_sn=self.c['parcel_sn'],
                      return_document_count=1, return_sn=self.c['return_sn'], shared_parcel=self.parcels()[0])
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')

    def verify_ui(self):
        super().verify_ui()
        from playwright.sync_api import sync_playwright, expect
        before, errors = self.snapshot(), []
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': self.token, 'url': BASE}])
            page = context.new_page(); page.set_default_timeout(30000)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(self.c['refunds']['kit']['id']), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name='业务退款详情 · 退货退款', exact=True)
            dialog.get_by_role('tab', name='退回与包裹', exact=True).click()
            dialog.get_by_role('button', name='查看包裹及处理实收', exact=True).last.click()
            parcel = page.locator('.refund-parcel-drawer')
            expect(parcel.get_by_text('合包退回 · 4 张退款单', exact=True)).to_be_visible()
            expect(parcel).to_contain_text('共同退货入库单 ' + self.c['return_sn'])
            expect(parcel).to_contain_text('TEST_MERGED_' + self.c['case_id'])
            page.screenshot(path=str(OUT / 'shared-return-parcel.png'), full_page=True)
            assert not errors, errors
            browser.close()
        assert self.snapshot() == before
        self.check('浏览器确认同一退回运单、4张退款和1张共同THK，购买商品与赠品各自实收入账')


if __name__ == '__main__':
    Acceptance().run()
