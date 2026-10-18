"""Fresh local TEST-only return/outbound cancellation acceptance; preserves an inspection case."""
import hashlib
import json
import subprocess
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
from wms_qimen_simulator import sign
from verify_negotiated_exchange import partial_return
import verify_exchange_ownership as ownership

OUT = Path(__file__).resolve().parents[2] / 'outputs/exchange-controls'
INVENTORY = 'qm_oms_saas_inventory'


def actual(case, e):
    return case.api('/order/exchanges/' + str(e['id']) + '/return')['data']['actual']


def doc_action(case, e, doc, action, body=None):
    return case.act(e, 'return-documents/' + str(doc['id']) + '/' + action,
                    {'document_version': doc['control_version'], **(body or {})})


def cutoff(case, e, kind, document=0, mode='WITHDRAW'):
    return case.act(e, 'control/request', {'kind': kind, 'document_id': document, 'mode': mode,
                                         'reason': '[验收] 出入库截单和实收实发保留'})


def dispatch_only(case, sh):
    sh = case.shipment(sh['id'])
    if sh['carrier_match_status'] != 'MATCHED':
        r = case.api('/order/orders/shipments/match-carrier', {'items': [{'id': sh['id'], 'control_version': sh['control_version']}]})['data']
        assert r[0]['success'], r
        sh = case.shipment(sh['id'])
    if sh['wms_dispatch_state'] == 'NONE':
        r = case.api('/order/orders/shipments/dispatch', {'items': [{'id': sh['id'], 'control_version': sh['control_version']}]})['data']
        assert r[0]['success'], r
    sh = case.shipment(sh['id'])
    if sh['wms_dispatch_state'] == 'PENDING':
        case.api('/order/orders/shipments/' + str(sh['id']) + '/continue-dispatch', {'control_version': sh['control_version']})
    return wait_for(lambda: case.shipment(sh['id']), lambda x: x['wms_dispatch_state'] == 'ACCEPTED', 'TEST exchange dispatch incomplete')


def late_receipt(case, e):
    report = actual(case, e)
    doc = next(d for d in report['documents'] if d['generation'] == 1)
    connection = next(x for x in case.api('/supplychain/wmsIntegration/connections')['data'] if x['id'] == case.c['connection_id'])
    assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
    credential = next(x for x in json.loads(Path(__file__).with_name('wms-simulator-credentials.json').read_text('utf-8')) if x['app_key'] == connection['app_key'] and x['customer_id'] == connection['customer_id'])
    # The test warehouse records its actual late receipt before notifying OMS.
    payload = {'customer': connection['customer_id'], 'owner': report['external_owner'], 'warehouse': report['external_warehouse'], 'sn': doc['return_sn']}
    script = """import json,sqlite3
p=json.loads(PAYLOAD)
with sqlite3.connect('/data/documents.sqlite',timeout=10) as db:
 row=db.execute("SELECT quantity,status FROM documents WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?",tuple(p[k] for k in ['customer','owner','warehouse','sn'])).fetchone()
 assert row and row[0]==2 and row[1] in ('CANCELED','FULFILLED')
 db.execute("UPDATE documents SET status='FULFILLED' WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?",tuple(p[k] for k in ['customer','owner','warehouse','sn']))
""".replace('PAYLOAD', repr(json.dumps(payload)))
    result = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-wms-simulator', 'python', '-'], input=script.encode(), capture_output=True, timeout=30)
    assert result.returncode == 0, 'TEST warehouse late receipt identity failed'
    root = ET.Element('request'); head = ET.SubElement(root, 'returnOrder')
    for k, v in {'returnOrderCode': doc['return_sn'], 'returnOrderId': doc['external_order'], 'warehouseCode': report['external_warehouse'], 'ownerCode': report['external_owner'], 'outBizCode': 'TEST_LATE_' + str(e['id']), 'orderType': 'HHRK', 'confirmType': '0'}.items():
        ET.SubElement(head, k).text = str(v)
    line = ET.SubElement(ET.SubElement(root, 'orderLines'), 'orderLine')
    for k, v in {'itemCode': case.c['sku'], 'ownerCode': report['external_owner'], 'actualQty': 1, 'inventoryType': 'ZP', 'batchCode': 'TEST_LATE_' + str(e['id'])}.items():
        ET.SubElement(line, k).text = str(v)
    body = ET.tostring(root, encoding='unicode')
    params = {'app_key': connection['app_key'], 'customerId': connection['customer_id'], 'method': 'taobao.qimen.returnorder.confirm', 'v': '2.0', 'format': 'xml', 'sign_method': 'md5', 'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
    params['sign'] = sign(params, body, credential['secret'])
    req = urllib.request.Request(BASE + '/prod-api/supplychain/wmsCallback/qm/' + connection['callback_key'] + '?' + urllib.parse.urlencode(params), data=body.encode(), headers={'Content-Type': 'application/xml;charset=UTF-8'}, method='POST')
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=40) as response:
        ack = ET.fromstring(response.read())
    assert ack.findtext('flag') == 'success', ack.findtext('message')


def ensure(case, source, label):
    c = case.c
    if not c.get(label + '_id'):
        recipient = case.api('/order/exchanges/create-sources?orderId=' + str(c['order_id']))['data']['recipient']
        created = case.api('/order/exchanges', {'order_id': c['order_id'], 'order_line_id': source['order_line_id'], 'platform_oid': c['platform_oid'], 'source_shipment_line_id': source['id'], 'fulfillment_mode': 'RETURN', 'source_phase': 'AFTER_SALE', 'quantity': 2, 'target_sku': c['target_sku'], 'return_store': c['warehouses'][0]['code'], 'return_address': '[验收] 无实际配送的寄回地址', 'return_contact': 'TEST-CONTACT', 'replacement_recipient': recipient, 'reason': '[验收] 换货截单 ' + label, 'request_key': 'CONTROL_' + label + '_' + c['case_id']})['data']
        c[label + '_id'] = created['id']; case.save()
    e = case.exchange({'id': c[label + '_id']})
    if not e.get('approved_version'):
        case.act(e, 'review', {'target_sku': c['target_sku'], 'source_shipment_line_id': source['id'], 'return_store': c['warehouses'][0]['code'], 'return_address': '[验收] 无实际配送的寄回地址', 'return_contact': 'TEST-CONTACT', 'recipient_confirmed': True, 'replacement_recipient': e['payload']['replacement_recipient'], 'reason': '[验收] 核对退回来源'})
    e = case.exchange(e)
    if not e['return_sn']:
        case.act(e, 'advance'); e = case.exchange(e)
    return e


def main():
    OUT.mkdir(exist_ok=True); ownership.OUT = OUT; token = login(); case = ownership.Case('cut', token)
    try:
        case.prepare(); c = case.c
        original = case.dispatch_ship(case.allocate()['shipments'][0]); source = original['lines'][0]
        e = ensure(case, source, 'complete')
        if not c.get('lifecycle_verified'):
            if not e.get('fulfillment_line_id'):
                if not c.get('partial_complete'):
                    partial_return(case, e); c['partial_complete'] = True; case.save()
                report = actual(case, e); first = next(d for d in report['documents'] if d['generation'] == 1)
                if first['cancel_state'] != 'CONFIRMED':
                    cutoff(case, e, 'RETURN', first['id'])
                report = actual(case, e)
                if len(report['documents']) == 1:
                    doc_action(case, e, report['documents'][0], 'rearrange', {'quantity': 1, 'store_code': c['warehouses'][0]['code'], 'return_address': '[验收] 重新安排地址', 'return_contact': 'TEST-CONTACT', 'reason': '[验收] 只重新安排剩余1件'})
                report = actual(case, e); assert report['document_count'] == 2 and report['posted_quantity'] == 1
                assert case.exchange(e)['fulfillment_hold'] == 1 and report['documents'][0]['quantity'] == 1
                before = sql("SELECT total_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn=" + quote(c['sku']), INVENTORY)
                late_receipt(case, e); late_receipt(case, e)
                report = actual(case, e); assert report['pending_review_count'] == 1 and report['posted_quantity'] == 1
                assert before == sql("SELECT total_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn=" + quote(c['sku']), INVENTORY)
                cutoff(case, e, 'RETURN', report['documents'][0]['id'])
                report = actual(case, e)
                # A pending late receipt must first be resolved; new local arrangement can be withdrawn without releasing receipts.
                first = next(d for d in report['documents'] if d['generation'] == 1)
                review = next(r for r in report['reviews'] if r['state'] == 'WAIT_REVIEW')
                doc_action(case, e, first, 'review', {'review_id': review['id'], 'decision': 'ACCEPT', 'reason': '[验收] 仓库实收确认', 'evidence': 'TEST-LATE-CONFIRMED'})
                report = actual(case, e); assert report['posted_quantity'] == 2 and report['pending_review_count'] == 0
                case.act(e, 'control/recheck'); case.act(e, 'control/resume')
                case.act(e, 'qc', {'decision': 'PASSED', 'reason': '[验收] 原单实收2件已入账'})
                case.act(e, 'advance'); case.act(e, 'allocate'); e = case.exchange(e)
            e = case.exchange(e)
            if len(e['shipments']) == 1:
                first_ship = e['shipments'][0]
                if first_ship['status'] != 'VOIDED':
                    first_ship = dispatch_only(case, first_ship); cutoff(case, e, 'OUTBOUND', first_ship['id'])
                e = case.exchange(e); stock = e['stock_receipt'] if isinstance(e['stock_receipt'],dict) else json.loads(e['stock_receipt'])
                assert e['allocation_quantity'] == 0 and stock['reserved_quantity'] == 2
                if e['control_state'] == 'HOLD':case.act(e, 'control/resume')
                case.act(e, 'allocate'); e = case.exchange(e)
            if e['state'] != 'CLOSED':
                fresh = [s for s in e['shipments'] if s['status'] != 'VOIDED']
                if e['control_state'] not in ('CANCEL_REVIEW','CANCELING'):
                    assert len(e['shipments']) == 2 and len(fresh) == 1
                    dispatch_only(case, fresh[0]); cutoff(case, e, 'CANCEL', mode='TERMINATE')
                e = case.exchange(e); stock = e['stock_receipt'] if isinstance(e['stock_receipt'],dict) else json.loads(e['stock_receipt'])
                assert e['control_state'] == 'CANCEL_REVIEW' and stock['reserved_quantity'] == 0
                case.act(e, 'control/close', {'reason': '[验收] 剩余停止，退回已收商品保留，后续协商另行处理', 'evidence': 'TEST-CLOSE-NO-REFUND-EXECUTED'})
            e = case.exchange(e); assert e['state'] == 'CLOSED' and e['return_result']['posted_quantity'] == 2
            c['lifecycle_verified'] = True; case.save()
        pending = ensure(case, source, 'pending')
        if not c.get('pending_partial'):
            partial_return(case, pending); c['pending_partial'] = True; case.save()
        report = actual(case, pending)
        first_pending = next(d for d in report['documents'] if d['generation'] == 1)
        if first_pending['cancel_state'] != 'CONFIRMED':
            cutoff(case, pending, 'RETURN', first_pending['id'])
        report = actual(case, pending)
        if len(report['documents']) == 1:
            doc_action(case, pending, report['documents'][0], 'rearrange', {'quantity': 1, 'store_code': c['warehouses'][0]['code'], 'return_address': '[验收] 剩余1件补退地址', 'return_contact': 'TEST-CONTACT', 'reason': '[验收] 保留两张退回单用于验收'})
        pending = case.exchange(pending); report = actual(case, pending)
        assert report['posted_quantity'] == 1 and report['document_count'] == 2 and pending['fulfillment_hold'] == 1
        assert case.order()['shipped_quantity'] == 10 and case.order()['manual_hold'] == 0
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True); ctx = browser.new_context(viewport={'width': 1600, 'height': 1150})
            ctx.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}]); page = ctx.new_page(); errors = []
            page.on('pageerror', lambda e: errors.append(str(e))); page.goto(BASE + '/oms-order/exchanges?exchangeId=' + str(pending['id']), wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible'); expect(drawer.get_by_text('新增履约已挂起', exact=True)).to_be_visible()
            drawer.get_by_role('button',name='取消换货',exact=True).click(); cancel=page.get_by_role('dialog',name='取消换货 · 协调出入库截单',exact=True); expect(cancel).to_be_visible(); cancel.get_by_role('button',name='返回',exact=True).click()
            drawer.get_by_role('tab', name='退回单', exact=True).click(); panel = drawer.locator('.return-documents')
            expect(panel.locator('.return-document')).to_have_count(2); expect(panel.get_by_text('第 2 次安排', exact=True)).to_be_visible()
            page.screenshot(path=str(OUT / 'return-documents.png'), full_page=False, animations='disabled')
            drawer.get_by_role('tab', name='换出发货', exact=True).click(); expect(drawer.get_by_text('换出单截单管理', exact=True)).to_be_visible()
            assert not errors, errors; browser.close()
        c.update(verified=True, closed_exchange_id=e['id'], closed_exchange_sn=e['exchange_sn'], pending_exchange_id=pending['id'], pending_exchange_sn=pending['exchange_sn'])
        case.save(); print('PASS exchange controls: partial return, replan, late review, outbound withdrawal/reallocation, HH closure and UI; pending id', pending['id'])
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
