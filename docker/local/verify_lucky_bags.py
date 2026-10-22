"""Retained local TEST case: protect components, allocate bags, ship and return original units.

Only simulator platform/WMS credentials and newly owned fixture records are used.
"""
import io
import json
import subprocess
import time
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
import openpyxl
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
from wms_qimen_simulator import sign
import verify_exchange_ownership as ownership
import verify_allocation_rules_v2 as fixture

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/lucky-bags'
INV, ORDER, CH = 'qm_oms_saas_inventory', 'qm_oms_saas_order', 'qm_oms_saas_channel'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ownership.OUT = OUT
    credential = login()
    case = ownership.Case('luckybag', credential)
    c = case.c

    def api(path, body=None):
        r = request(path, payload=body, token=credential, method='POST' if body is not None else 'GET')
        return r.get('data', r)

    def inner(app, port, path, body=None):
        command = ['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-' + app, 'curl', '-fsS', '-X', 'POST', '-H', 'from-source: inner', '-H', 'Content-Type: application/json']
        command += ['--data-binary', '@-', 'http://localhost:' + str(port) + path]
        r = subprocess.run(command, input=json.dumps(body or {}).encode(), capture_output=True, timeout=60)
        assert r.returncode == 0, 'Local TEST service unavailable'
        reply = json.loads(r.stdout)
        assert reply['code'] == 200, reply.get('msg')
        return reply.get('data', reply)

    def totals():
        return {s: int(sql('SELECT COALESCE(SUM(total_stock),0) FROM oms_inventory WHERE company_code=\'QM\' AND sku_sn=' + quote(s), INV)) for s in [c['sku'], c['a'], c['b'], c['d']]}

    def allocate():
        if c.get('allocation_id'):
            return
        draft = {'ruleName': '[福袋验收] ' + c['case_id'], 'ruleType': 2, 'allocationType': 1, 'ruleRange': 2, 'ruleMode': 2, 'stores': [c['warehouses'][0]['code']], 'channels': [{'channelId': c['channel_id'], 'channelName': c['channel_name'], 'ruleType': 2, 'percentage': 3, 'decimalHandleType': 1}]}
        header = api('/inventory/allocation', draft)
        rid = header['id']
        book = openpyxl.Workbook()
        book.active.append(['SKU(必填)'])
        book.active.append([c['sku']])
        stream = io.BytesIO()
        book.save(stream)
        boundary = 'LuckyBagFixtureBoundary'
        wire = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="lucky.xlsx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode() + stream.getvalue() + ('\r\n--' + boundary + '--\r\n').encode()
        req = urllib.request.Request(BASE + '/prod-api/inventory/allocation/' + str(rid) + '/goods?revision=' + str(header['revision']), data=wire, headers={'Authorization': 'Bearer ' + credential, 'Content-Type': 'multipart/form-data; boundary=' + boundary}, method='POST')
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=40) as r:
            result = json.load(r)
        assert result['code'] == 200, result.get('msg')
        header = result['data']
        header = api('/inventory/allocation/' + str(rid) + '/submit?revision=' + str(header['revision']), {})
        api('/inventory/allocation/' + str(rid) + '/start?revision=' + str(header['revision']) + '&action=EXECUTE', {})
        begin = time.perf_counter()
        result = api('/inventory/allocation/' + str(rid) + '/step', {})
        assert result['status'] == 5, api('/inventory/allocation/' + str(rid) + '/results')
        c.update(allocation_id=rid, allocation_ms=round((time.perf_counter()-begin)*1000, 2))
        case.save()

    def platform_setup():
        ch=c['channel_id']
        if not c.get('sync_group'):
            app=next(a for a in api('/channel/platform/apps') if int(a.get('simulation_mode',0))==1)
            binding=api('/channel/platform/shops/'+str(ch))
            if not binding.get('app_id'):api('/channel/platform/shops/'+str(ch)+'/binding',{'app_id':app['id'],'expected_shop_id':'9900'+str(ch)})
            api('/channel/platform/shops/'+str(ch)+'/simulate',{})
            assert api('/channel/platform/shops/'+str(ch))['simulation'] is True
            sql("UPDATE t_channel SET sync_enabled=1 WHERE UPPER(company_code)='QM' AND channel_id="+str(ch),CH)
            for i in range(1,3):
                item='98'+c['case_id']+str(i);sku='97'+c['case_id']+str(i)
                product={'num_iid':item,'title':'[福袋验收] 链接'+str(i),'approve_status':'onsale','num':0,'skus':{'sku':[{'sku_id':sku,'outer_id':c['sku'],'properties':'1:'+str(i),'properties_name':'福袋规格'+str(i),'quantity':0,'status':'normal'}]}}
                sql("INSERT IGNORE INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(json.dumps(product,ensure_ascii=False))+")",CH)
                sql("INSERT IGNORE INTO channel_stock_sync_sim(company_code,channel_id,item_id,sku_id,quantity) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(sku)+",0)",CH)
            task=api('/channel/platformProducts/pull/'+str(ch),{'mode':'ALL'})
            for _ in range(6):
                result=inner('channel',9304,'/platformProducts/internal/process/'+str(task['id'])+'?company_code=QM')
                if result['state']=='SUCCESS':break
            assert result['state']=='SUCCESS',result
            products=api('/channel/platformProducts?channelId='+str(ch))['rows'];assert len(products)==2
            links=[]
            for p in sorted(products,key=lambda p:p['item_id']):
                bound=api('/channel/platformProducts/'+str(p['id'])+'/binding',{'action':'BIND','sku_sn':c['sku'],'binding_revision':p['binding_revision'],'reason':'[福袋验收] 明确绑定原销售SKU'})
                links.append({'binding_id':bound['id'],'binding_revision':bound['binding_revision'],'name':p['title'],'weight':1,'enabled':1,'quantity_cap':None})
            c['sync_group']=api('/channel/stockSync',{'channel_id':ch,'sku_sn':c['sku'],'safety_stock':0,'enabled':0,'quantity_cap':None,'links':links})['id'];case.save()
    def synchronize(expected):
        ch=c['channel_id'];assert api('/channel/platform/shops/'+str(ch))['simulation'] is True
        # Only this fixture's intake heartbeat is advanced; no remote source is enabled for polling.
        sql("UPDATE oms_order_pull_config SET enabled=1,cursor_time=NOW(),last_error='',next_run_at=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE company_code='QM' AND channel_id="+str(ch),ORDER)
        d=inner('channel',9304,'/stockSync/internal/execute/'+str(c['sync_group'])+'?company_code=QM')
        assert d['state']=='SUCCESS',d.get('last_error')
        q=int(sql("SELECT COALESCE(SUM(quantity),0) FROM channel_stock_sync_sim WHERE company_code='QM' AND channel_id="+str(ch),CH));assert q==expected,(q,expected)
        ledger=api('/inventory/lucky-bags/'+c['sku']);assert ledger['exposures'] and sum(e['quantity'] for e in ledger['exposures'])==expected
        assert not any(e['uncertain'] for e in ledger['exposures']),ledger['exposures']
        sql("UPDATE oms_order_pull_config SET enabled=0 WHERE company_code='QM' AND channel_id="+str(ch),ORDER)

    try:
        if not c.get('initialized'):
            c.update(sku='FD' + c['case_id'], a='FD' + c['case_id'] + 'A', b='FD' + c['case_id'] + 'B', d='FD' + c['case_id'] + 'D', seller='LUCKY_' + c['case_id'], channel_name='[福袋验收] ' + c['case_id'], received=True, warehouses=[{'code': 'FD_V_' + c['case_id'], 'quantity': 0, 'package_count': 1}])
            case.save()
            sample = api('/goods/info/list?pageNum=1&pageSize=1', {})['rows'][0]
            for sku, bag in [(c['a'], 0), (c['b'], 0), (c['d'], 0), (c['sku'], 1)]:
                if not api('/goods/info/list?pageNum=1&pageSize=5', {'skuSn': sku})['rows']:
                    api('/goods/info', {'skuSn': sku, 'goodsSn': sku, 'goodsName': '[福袋验收] ' + ('销售福袋' if bag else '实际商品 ' + sku[-1]), 'categoryCode': sample['categoryCode'], 'colorCode': sample['colorCode'], 'sizeCode': sample['sizeCode'], 'marketPrice': 10, 'validity': 30, 'isFd': bag, 'isPackage': 0})
            c['goods_id'] = api('/goods/info/list?pageNum=1&pageSize=5', {'skuSn': c['sku']})['rows'][0]['id']
            rule = api('/goods/info/' + str(c['goods_id']) + '/lucky-bag')
            if rule['revision'] == 0:
                api('/goods/info/' + str(c['goods_id']) + '/lucky-bag/publish', {'revision': 0, 'draft_revision': rule['draft_revision'], 'enabled': 1, 'mode': 'PRESET', 'preset_quantity': 3, 'selection': 'CLEARANCE', 'max_cost': 5, 'groups': [{'key': 'top', 'name': '上装', 'candidates': [{'sku_sn': c['a'], 'weight': 1, 'clearance_priority': 10, 'fixed_quantity': 2}, {'sku_sn': c['b'], 'weight': 1, 'clearance_priority': 0, 'fixed_quantity': 1}]}, {'key': 'bottom', 'name': '下装', 'candidates': [{'sku_sn': c['d'], 'weight': 1, 'fixed_quantity': 3}]}]})
            c['initialized'] = True
            case.save()

        def before_conversion(current, token):
            if not c.get('components_ready'):
                for index, sku in enumerate([c['a'], c['b'], c['d']], 1):
                    inner('inventory', 9303, '/api/WmsInventory/addInventory?company_code=QM', {'relationSn': 'FDIN' + c['case_id'] + str(index), 'wmsInventoryBatch': {'companyCode': 'QM', 'storeCode': c['warehouses'][0]['code'], 'skuSn': sku, 'batchCode': 'FD-' + c['case_id'] + '-' + str(index), 'zpActualNumber': 10, 'cpActualNumber': 0, 'transactionPrice': 2}})
                c['components_ready'] = True
                case.save()
            allocate()
            platform_setup()
            if not c.get('initial_sync'):
                synchronize(3);c['initial_sync']=True;case.save()

        if not c.get('prepared'):
            fixture.MANIFEST = case.path
            fixture.prepare_case(c, credential, before_conversion=before_conversion, order_quantity=2)
            c['prepared'] = True
            case.save()
        o = case.order()
        bag = o['bundles'][0]
        assert bag['business_type'] == 'LUCKY_BAG' and bag['quantity'] == 2 and bag['component_quantity'] == 4
        assert len(bag['units']) == 2 and {l['platform_oid'] for l in o['lines']} == {c['platform_oid']}
        assert all(l['payment'] == 0 for l in o['lines']) and bag['payment'] == 10
        ledger = api('/inventory/lucky-bags/' + c['sku'])
        assert sum(p['quantity'] for p in ledger['pools']) == 3
        assert sum(s['quantity'] for p in ledger['pools'] for s in p['components'] if s['sku_sn'] == c['a']) == 2
        assert sum(s['quantity'] for p in ledger['pools'] for s in p['components'] if s['sku_sn'] == c['b']) == 1
        assert all(float(u['cost']) <= 5 for u in bag['units']) and totals()[c['sku']] == 0
        if not c.get('version_checked'):
            rule = api('/goods/info/' + str(c['goods_id']) + '/lucky-bag')
            draft = rule['draft']
            draft.update(revision=rule['revision'], draft_revision=rule['draft_revision'])
            draft['groups'][0]['name'] = '上装（新版本）'
            api('/goods/info/' + str(c['goods_id']) + '/lucky-bag/publish', draft)
            assert case.order()['bundles'][0]['revision'] == 1
            c['version_checked'] = True
            case.save()
        o = case.allocate()
        assert len(o['shipments']) == 1 and o['shipments'][0]['quantity'] == 4
        for shipment in o['shipments']:
            assert all(l['sku_sn'] != c['sku'] and l['source_type'] == 'LUCKY' for l in case.shipment(shipment['id'])['lines'])
            case.dispatch_ship(shipment)
        o = case.order()
        assert o['bundles'][0]['shipped_sets'] == 2 and all(u['shipped_complete'] for u in o['bundles'][0]['units'])
        for shipment in o['shipments']:
            api('/order/orders/shipments/' + str(shipment['id']) + '/platform-shipping', {})
        reports = [api('/order/orders/shipments/' + str(s['id']) + '/platform-shipping') for s in o['shipments']]
        assert sum(int(t['snapshot']['goods'].get(c['platform_oid'], 0)) for r in reports for t in r['tasks']) == 2

        if not c.get('refund_id'):
            source = next(s for s in api('/order/orders/sources') if s.get('database_name') == 'refund_acceptance_source')
            if not any(config['channel_id']==c['channel_id'] for config in api('/order/refunds/configs')['configs']):api('/order/refunds/configs', {'channel_id': c['channel_id'], 'source_key': source['source_key'], 'seller_nick': c['seller'], 'initial_time': c['created'], 'revision': 0, 'enabled': 0, 'auto_convert': 0, 'interval_seconds': 30})
            rid = '997' + c['case_id'] + '77'
            modified = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            raw = {'refund_id': rid, 'tid': c['platform_tid'], 'oid': c['platform_oid'], 'seller_nick': c['seller'], 'status': 'WAIT_SELLER_CONFIRM_GOODS', 'refund_fee': '5.00', 'num': 1, 'has_good_return': True, 'dispute_type': 'REFUND_AND_RETURN', 'refund_phase': 'aftersale', 'refund_version': '1', 'reason': '[验收] 选择一袋按原组成退回', 'created': modified, 'modified': modified}
            values = [rid, c['platform_tid'], c['platform_oid'], raw['status'], c['seller'], modified, modified, modified, json.dumps({'refund_get_response': {'refund': raw}}, ensure_ascii=False)]
            sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES (' + ','.join(quote(str(v)) for v in values) + ')', 'refund_acceptance_source')
            task = api('/order/refunds/sync', {'channel_id': c['channel_id'], 'mode': 'IDS', 'refund_ids': [rid], 'request_key': 'FDREF_' + c['case_id']})
            wait_for(lambda: api('/order/refunds/tasks/' + str(task['id'])), lambda t: t['state'] == 'COMPLETED', 'TEST refund intake incomplete', 150)
            platform = api('/order/refunds/platform?number=' + rid)['rows'][0]
            result = api('/order/refunds/platform/' + str(platform['id']) + '/convert', {})
            if result['state']=='PENDING':
                platform=wait_for(lambda:api('/order/refunds/platform/'+str(platform['id'])),lambda p:bool(p.get('business_refund_id')),'TEST refund conversion incomplete',150)
                c['refund_id']=platform['business_refund_id']
            else:
                assert result['state']=='SUCCESS',result
                c['refund_id']=result['id']
            case.save()
        rb = '/order/refunds/business/' + str(c['refund_id'])
        refund = api(rb)
        if refund['review_state'] != 'APPROVED':
            api(rb + '/review', {'control_version': refund['control_version'], 'platform_version': refund['platform']['version'], 'purpose': 'CANCEL', 'goods_action': 'RETURN', 'reason': '[验收] 原袋退回', 'evidence': 'TEST-LUCKY-BAG'})
        refund = api(rb)
        c['refund_sn'] = refund['refund_sn']
        case.save()
        if not api(rb + '/goods')['bundle_returns']:
            before = totals()
            selected = o['bundles'][0]['units'][0]
            api(rb + '/goods/bundle-plans', {'control_version': refund['control_version'], 'bundle_id': bag['id'], 'unit_ids': [selected['unit_id']], 'quantity': 1, 'store_code': c['warehouses'][0]['code'], 'return_address': '[验收] 无实际寄回', 'return_contact': 'TEST-LUCKY', 'reason': '[验收] 原袋实际出库组成', 'evidence': 'TEST-LUCKY-BAG', 'confirmed': True})
            assert totals() == before, 'Planning a return must not create stock'
        if not c.get('reclaim_checked'):
            synchronize(1)
            result=api('/inventory/lucky-bags/'+c['sku']+'/release',{'rule_id':c['allocation_id'],'confirmed':True});assert result['state']=='WAIT_CONFIRM'
            synchronize(0)
            result=api('/inventory/lucky-bags/'+c['sku']+'/release',{'rule_id':c['allocation_id'],'confirmed':True});assert result['state']=='RELEASED',result
            c['reclaim_checked']=True;case.save()
        goods = api(rb + '/goods')
        assert sum(p['quantity'] for p in goods['plans']) == 2 and len(goods['bundle_returns'][0]['unit_ids']) == 1

        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + '/oms-goods/lucky-bag/index?id=' + str(c['goods_id']), wait_until='networkidle')
            expect(page.get_by_role('tab', name='库存与履约', exact=True)).to_be_visible()
            page.get_by_role('tab', name='库存与履约', exact=True).click()
            expect(page.get_by_role('button', name='福袋保障与回收', exact=True)).to_be_visible()
            page.screenshot(path=str(OUT / 'rule.png'), animations='disabled')
            page.get_by_role('button', name='福袋保障与回收', exact=True).click()
            expect(page.get_by_role('dialog', name='福袋库存 · ' + c['sku'], exact=True)).to_contain_text('平台曝光保护（袋）')
            page.screenshot(path=str(OUT / 'protection.png'), animations='disabled')
            page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(c['order_id']), wait_until='networkidle')
            page.get_by_role('tab', name='商品明细', exact=True).click()
            expect(page.locator('.sales-bundles').first).to_contain_text('完整发出 2 袋')
            expect(page.locator('.sales-bundles').first).to_contain_text(c['platform_oid'])
            page.screenshot(path=str(OUT / 'order.png'), animations='disabled')
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(c['refund_id']), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name='业务退款详情 · 退货退款', exact=True)
            dialog.get_by_role('tab', name='退回与包裹', exact=True).click()
            expect(dialog).to_contain_text('原袋退回')
            dialog.get_by_role('button', name='创建退回安排', exact=True).click()
            form = page.get_by_role('dialog', name='创建退款退回安排', exact=True)
            form.get_by_text('原销售组合整袋 / 整套退回', exact=True).click()
            expect(form).to_contain_text('选择原袋')
            expect(form).to_contain_text('已安排整袋退回')
            page.screenshot(path=str(OUT / 'refund.png'), animations='disabled')
            assert not errors, errors
            browser.close()
        # Receive only the selected original bag. Repeated WMS callbacks cannot post twice.
        if 'before_return' not in c:
            c['before_return'] = totals()
            case.save()
        connection = next(x for x in api('/supplychain/wmsIntegration/connections') if x['id'] == c['connection_id'])
        assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
        secret = next(x for x in json.loads((ROOT / 'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key'] == connection['app_key'] and x['customer_id'] == connection['customer_id'])
        for original in api(rb + '/goods')['plans']:
            plan = next(p for p in api(rb + '/goods')['plans'] if p['id'] == original['id'])
            if not plan['actual']:
                api(rb + '/goods/plans/' + str(plan['id']) + '/documents/0/dispatch', {'control_version': api(rb)['control_version'], 'plan_version': plan['control_version']})
                api(rb + '/goods/refresh', {})
                plan = next(p for p in api(rb + '/goods')['plans'] if p['id'] == original['id'])
            actual = plan['actual']
            document = actual['documents'][0]
            assert document['dispatch_state'] == 'ACCEPTED' and document['quantity'] == 1
            root = ET.Element('request')
            head = ET.SubElement(root, 'returnOrder')
            for k, v in {'returnOrderCode': document['return_sn'], 'returnOrderId': document['external_order'], 'warehouseCode': actual['external_warehouse'], 'ownerCode': actual['external_owner'], 'outBizCode': 'FD_RETURN_' + str(plan['id']), 'orderType': 'THRK', 'confirmType': '0'}.items():
                ET.SubElement(head, k).text = str(v)
            line = ET.SubElement(ET.SubElement(root, 'orderLines'), 'orderLine')
            for k, v in {'itemCode': plan['sku_sn'], 'ownerCode': actual['external_owner'], 'actualQty': 1, 'inventoryType': 'ZP', 'batchCode': 'FD_RETURN_' + str(plan['id'])}.items():
                ET.SubElement(line, k).text = str(v)
            wire = ET.tostring(root, encoding='unicode')
            (OUT / ('return-' + str(plan['id']) + '.xml')).write_text(wire, encoding='utf-8')
            marker = {'customer': connection['customer_id'], 'owner': actual['external_owner'], 'warehouse': actual['external_warehouse'], 'sn': document['return_sn'], 'external': document['external_order']}
            script = "import json,sqlite3\np=json.loads(" + repr(json.dumps(marker)) + ")\nwith sqlite3.connect('/data/documents.sqlite',timeout=10) as db:\n row=db.execute(\"SELECT external_id,quantity,status FROM documents WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?\",tuple(p[k] for k in ['customer','owner','warehouse','sn'])).fetchone()\n assert row and row[0]==p['external'] and row[1]==1 and row[2] in ('ACCEPT','FULFILLED')\n db.execute(\"UPDATE documents SET status='FULFILLED' WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?\",tuple(p[k] for k in ['customer','owner','warehouse','sn']))\n"
            marked = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-wms-simulator', 'python', '-'], input=script.encode(), capture_output=True, timeout=30)
            assert marked.returncode == 0, 'TEST return document identity mismatch'
            for replay in range(2):
                before_replay = totals()
                params = {'app_key': connection['app_key'], 'customerId': connection['customer_id'], 'method': 'taobao.qimen.returnorder.confirm', 'v': '2.0', 'format': 'xml', 'sign_method': 'md5', 'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
                params['sign'] = sign(params, wire, secret['secret'])
                req = urllib.request.Request(BASE + '/prod-api/supplychain/wmsCallback/qm/' + connection['callback_key'] + '?' + urllib.parse.urlencode(params), data=wire.encode(), headers={'Content-Type': 'application/xml;charset=UTF-8'}, method='POST')
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=50) as response:
                    reply = ET.fromstring(response.read())
                assert reply.findtext('flag') == 'success', reply.findtext('message')
                api(rb + '/goods/refresh', {})
                if replay:
                    assert totals() == before_replay, 'Replayed TEST receipt must not create stock'
        received = api(rb + '/goods')
        assert sum(p['posted_quantity'] for p in received['plans']) == 2
        after_return = totals()
        assert sum(after_return.values()) == sum(c['before_return'].values()) + 2
        assert after_return[c['sku']] == 0
        assert sum(p['available_quantity'] for p in api('/inventory/lucky-bags/' + c['sku'])['pools']) == 0
        result = {'order_id': c['order_id'], 'order_sn': c['order_sn'], 'refund_id': c['refund_id'], 'refund_sn': c['refund_sn'], 'sku': c['sku'], 'bags_sold': 2, 'pieces_shipped': 4, 'bag_quota_original': 3, 'fixed_quantities': {'A': 2, 'B': 1, 'D': 3}, 'cost_cap': 5, 'parent_physical_stock': totals()[c['sku']], 'platform_shipped_bags': 2, 'return_bags': 1, 'return_pieces': 2, 'return_pieces_posted': 2, 'return_plan_adds_stock': False, 'receipt_does_not_restore_bag_quota': True, 'duplicate_receipt_posts_once': True, 'allocation_ms': c['allocation_ms'], 'shared_platform_budget': [3,1,0], 'safe_reclaim': True, 'real_platform_writes': False, 'browser_errors': []}
        (OUT / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('PASS TEST lucky bags: protect 3 bags / 6 pieces, sell and ship 2 bags / 4 pieces, return one original bag / 2 pieces, version snapshot and UI')
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
