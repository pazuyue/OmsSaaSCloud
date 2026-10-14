"""Explicit two-stage acceptance for the user-selected local TEST order.

Posts signed WMS receipts through normal inventory settlement. Durable case bodies
permit exact replay; no stock UPDATE, production shipment, or real TOP notification.
"""
import argparse
import hashlib
import json
import subprocess
import time
import urllib.parse
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from simulate_qimen_delivery import send

HERE = Path(__file__).resolve().parent
ORDER = 'SOb3c545e6286742cf8d176b320414f9be'
SKUS = {'ACCEPT_OUT_20261012164334': 2, 'test01': 4}


def save(path, case):
    temporary = path.with_suffix('.json.next')
    temporary.write_text(json.dumps(case, ensure_ascii=False, indent=2), encoding='utf8')
    temporary.replace(path)


def stock_snapshot(case):
    dimensions = {'tid': case['platform_tid'], 'batches': [line['batch_id'] for line in case['plans']], 'skus': list(SKUS)}
    script = '''import sys,json
sys.path.insert(0,'/mnt/d/Users/yueguang/OmsSaaSCloud/docker/local')
from local_db import execute,quote
d=json.loads(REPLACE_DIMENSIONS)
sku=','.join(quote(s) for s in d['skus']);batches=','.join(str(int(i)) for i in d['batches'])
scope="company_code='QM' AND channel_id=1 AND platform_tid="+quote(d['tid'])
queries={
'company':"SELECT id,sku_sn,total_stock,available_stock,allocated_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ("+sku+") ORDER BY id",
'channel':"SELECT id,sku_sn,available_stock,allocated_stock,reserved_stock,quota_stock,quota_reserved_stock,version FROM oms_channel_inventory WHERE company_code='QM' AND channel_id=1 AND sku_sn IN ("+sku+") ORDER BY id",
'warehouse':"SELECT id,sku_sn,zp_actual_number,zp_available_number,zp_lock_number,version FROM wms_inventory WHERE company_code='QM' AND store_code='VC0001' AND sku_sn IN ("+sku+") ORDER BY id",
'batch':"SELECT id,sku_sn,zp_actual_number,zp_available_number,zp_lock_number,version FROM wms_inventory_batch WHERE company_code='QM' AND id IN ("+batches+") ORDER BY id",
'demand':"SELECT id,sku_sn,demand_quantity,reserved_quantity,outbound_quantity FROM oms_order_stock_demand WHERE "+scope+" ORDER BY id",
'source':"SELECT s.id,s.sku_sn,s.quantity,s.outbound_quantity,s.reserved FROM oms_order_stock_source s JOIN oms_order_stock_demand d ON d.company_code=s.company_code AND d.id=s.demand_id WHERE d."+scope.replace(' AND ',' AND d.')+" ORDER BY s.id",
'allocation':"SELECT id,sku_sn,quantity,outbound_quantity,status,outbound_method FROM oms_order_warehouse_allocation WHERE "+scope+" ORDER BY id"
}
names={
'company':['id','sku_sn','total','available','allocated','reserved','version'],
'channel':['id','sku_sn','available','allocated','reserved','quota','quota_reserved','version'],
'warehouse':['id','sku_sn','actual','available','locked','version'],
'batch':['id','sku_sn','actual','available','locked','version'],
'demand':['id','sku_sn','quantity','reserved','outbound'],
'source':['id','sku_sn','quantity','outbound','reserved'],
'allocation':['id','sku_sn','quantity','outbound','status','method']
}
out={}
for key,sql in queries.items():
 out[key]=[dict(zip(names[key],[int(v) if v.isdigit() else v for v in line.split('\\t')])) for line in execute(sql,'qm_oms_saas_inventory').splitlines()]
print(json.dumps(out))
'''.replace('REPLACE_DIMENSIONS', repr(json.dumps(dimensions)))
    result = subprocess.run(['wsl.exe', '--', 'python3', '-'], input=script.encode('utf8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Scoped stock snapshot failed; no further callback submitted')
    return json.loads(result.stdout)


def mark_warehouse(case, stage, connection):
    payload = {'customer': connection['customer_id'], 'owner': case['external_owner'], 'warehouse': case['external_warehouse'], 'sn': case['shipment_sn'], 'external': case['external_order'], 'quantity': 6, 'status': 'PARTDELIVERED' if stage['phase'] == 1 else 'DELIVERED', 'action': 'SIM_DELIVERY_STAGE_' + str(stage['phase']), 'hash': hashlib.sha256(stage['callback_body'].encode('utf8')).hexdigest()}
    script = '''import json,sqlite3,datetime
from contextlib import closing
p=json.loads(REPLACE_PAYLOAD)
key=(p['customer'],'delivery',p['owner'],p['warehouse'],p['sn'])
with closing(sqlite3.connect('/data/documents.sqlite',timeout=10)) as db,db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE')
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row is not None and row['external_id']==p['external'] and row['quantity']==p['quantity'],'Simulation identity mismatch'
 old=db.execute("SELECT body_hash FROM events WHERE customer=? AND kind='delivery' AND sn=? AND action=?",(p['customer'],p['sn'],p['action'])).fetchall()
 if old:assert len(old)==1 and old[0]['body_hash']==p['hash'],'Simulation event content changed'
 else:
  assert row['status']==('ACCEPT' if p['status']=='PARTDELIVERED' else 'PARTDELIVERED'),'Unexpected simulation progress'
  db.execute('UPDATE documents SET status=? WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',(p['status'],*key))
  db.execute("INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,'delivery',?,?,?,?)",(p['customer'],p['sn'],p['action'],p['hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
'''.replace('REPLACE_PAYLOAD', repr(json.dumps(payload)))
    result = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-wms-simulator', 'python', '-'], input=script.encode('utf8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Local simulator identity or stage mismatch; callback not submitted')


def body(case, phase, carrier):
    root = ET.Element('request')
    head = ET.SubElement(root, 'deliveryOrder')
    for key, value in {'deliveryOrderCode': case['shipment_sn'], 'deliveryOrderId': case['external_order'], 'warehouseCode': case['external_warehouse'], 'ownerCode': case['external_owner'], 'status': 'PARTDELIVERED' if phase == 1 else 'DELIVERED', 'confirmType': '1' if phase == 1 else '0', 'outBizCode': 'SIM_STAGE_' + str(phase) + '_' + case['shipment_sn'], 'totalOrderLines': len(case['plans']), 'orderConfirmTime': datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')}.items():
        ET.SubElement(head, key).text = str(value)
    lines = ET.SubElement(root, 'orderLines')
    for line in case['plans']:
        item = ET.SubElement(lines, 'orderLine')
        for key, value in {'orderLineNo': line['id'], 'itemCode': line['sku_sn'], 'ownerCode': case['external_owner'], 'batchCode': line['batch_code'], 'inventoryType': 'ZP', 'actualQty': int(line['quantity']) // 2 * phase}.items():
            ET.SubElement(item, key).text = str(value)
    packages = ET.SubElement(root, 'packages')
    # Final callback includes both packages and cumulative quantities. The old
    # package is unchanged, so WMS normalization produces only the new 3-piece delta.
    for index in range(1, phase + 1):
        pack = ET.SubElement(packages, 'package')
        for key, value in {'packageCode': 'SIMPKG_' + case['shipment_sn'] + '_P' + str(index), 'expressCode': 'TEST-SF-' + case['shipment_sn'] + '-P' + str(index), 'logisticsCode': carrier['warehouse_carrier_code'], 'logisticsName': carrier['carrier_name']}.items():
            ET.SubElement(pack, key).text = value
        items = ET.SubElement(pack, 'items')
        for sku, quantity in SKUS.items():
            item = ET.SubElement(items, 'item')
            ET.SubElement(item, 'itemCode').text = sku
            ET.SubElement(item, 'quantity').text = str(quantity // 2)
    return ET.tostring(root, encoding='unicode')


def quantities(before, after, phase):
    for table in ['company', 'channel', 'warehouse', 'batch', 'demand', 'source', 'allocation']:
        assert len(before[table]) == len(after[table]) == 2, 'Unexpected scoped stock rows: ' + table
        prior = {row['id']: row for row in before[table]}
        for row in after[table]:
            old = prior[row['id']]
            quantity = SKUS[row['sku_sn']] // 2 * phase
            if table in ['warehouse', 'batch']:
                assert row['actual'] == old['actual'] - quantity and row['locked'] == old['locked'] - quantity
                assert row['available'] == old['available']
            elif table == 'company':
                assert row['total'] == old['total'] - quantity and row['reserved'] == old['reserved'] - quantity
            elif table == 'channel':
                assert row['reserved'] == old['reserved'] - quantity
            else:
                assert row['outbound'] == old['outbound'] + quantity and row['quantity'] == old['quantity']


def capture(token, case, phase, output):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1600, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        page = context.new_page()
        errors, writes = [], []
        page.on('pageerror', lambda e: errors.append(str(e)))
        def readonly(route):
            if route.request.method == 'GET': route.continue_()
            else: writes.append(route.request.url.split('?')[0]); route.abort()
        page.route('**/prod-api/**', readonly)
        page.goto(BASE + '/oms-order/shipments?number=' + case['shipment_sn'], wait_until='networkidle')
        expect(page.locator('.platform-shipping-status').first).to_contain_text('已模拟回传 ' + str(phase * 3))
        page.get_by_role('button', name=case['shipment_sn'], exact=True).first.click()
        drawer = page.locator('.shipment-detail:visible')
        expect(drawer.locator('.shipment-package-cards .package-card')).to_have_count(phase)
        expect(drawer.locator('.platform-summary')).to_contain_text('已模拟回传 ' + str(phase * 3))
        page.screenshot(path=str(output / ('phase-' + str(phase) + '-packages.png')), animations='disabled')
        drawer.locator('.platform-summary').get_by_role('button').click()
        dialog = page.locator('.platform-shipping-dialog:visible')
        expect(dialog.locator('.shipping-task')).to_have_count(phase)
        expect(dialog).to_contain_text('模拟成功 ' + str(phase * 3) + ' 件')
        page.screenshot(path=str(output / ('phase-' + str(phase) + '-platform.png')), animations='disabled')
        assert not errors and not writes
        browser.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('order_sn', choices=[ORDER])
    args = parser.parse_args()
    output = HERE.parents[1] / 'outputs/platform-shipping' / args.order_sn
    output.mkdir(parents=True, exist_ok=True)
    path = output / 'case.json'
    credential = login()
    try:
        orders = request('/order/orders/business/list?tid=' + args.order_sn, token=credential)['rows']
        order = next(row for row in orders if row['order_sn'] == args.order_sn)
        order_path = '/order/orders/business/' + str(order['id'])
        business = request(order_path, token=credential)['data']
        assert len(business['shipments']) == 1 and business['demand_quantity'] == 6
        shipment = business['shipments'][0]
        ship_path = '/order/orders/shipments/' + str(shipment['id'])
        detail = request(ship_path, token=credential)['data']
        route = detail['warehouse_target']
        assert route['environment'] == 'TEST' and route['provider'] == 'QIMEN'
        connection = next(c for c in request('/supplychain/wmsIntegration/connections', token=credential)['data'] if c['id'] == route['connection_id'])
        assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
        secret = next(c for c in json.loads((HERE / 'wms-simulator-credentials.json').read_text(encoding='utf8')) if c['app_key'] == connection['app_key'] and c['customer_id'] == connection['customer_id'])
        config = request('/order/orders/platform-shipping-config/1', token=credential)['data']
        assert config['mode'] == 'SIMULATION' and config['carrier_mapping']['SF'] == 'SF'
        if path.exists():
            case = json.loads(path.read_text(encoding='utf8'))
            assert case['shipment_id'] == detail['id'] and case['external_order'] == detail['wms_external_order']
        else:
            assert detail['shipped_quantity'] == 0 and not detail['packages'] and not detail['outbound_records']
            assert detail['wms_dispatch_state'] == 'ACCEPTED' and not any(i['blocking'] for i in business['issues'])
            plans = [{k: line[k] for k in ['id', 'sku_sn', 'quantity', 'platform_oid', 'store_code', 'batch_code']} for line in detail['dispatch_lines']]
            source_lines = {line['id']: line for line in detail['lines']}
            for line in plans: line['batch_id'] = source_lines[line['id']]['batch_id']
            assert {line['sku_sn']: line['quantity'] for line in plans} == SKUS and all(line['store_code'] == 'VC0001' for line in plans)
            case = {'order_id': order['id'], 'order_sn': args.order_sn, 'platform_tid': detail['platform_tid'], 'shipment_id': detail['id'], 'shipment_sn': detail['shipment_sn'], 'external_order': detail['wms_external_order'], 'external_owner': route['external_owner'], 'external_warehouse': route['external_warehouse'], 'plans': plans, 'stages': []}
            case['before_inventory'] = stock_snapshot(case)
            case['stages'] = [{'phase': phase, 'callback_body': body(case, phase, detail)} for phase in [1, 2]]
            save(path, case)
        for stage in case['stages']:
            phase = stage['phase']
            current = request(ship_path, token=credential)['data']
            if current['shipped_quantity'] > phase * 3 and stage.get('result'):
                continue
            mark_warehouse(case, stage, connection)
            send(stage, connection, secret)
            request(ship_path + '/reconcile-receipts', token=credential, method='POST')
            for _ in range(30):
                current = request(ship_path, token=credential)['data']
                if current['shipped_quantity'] == phase * 3: break
                if any(r['state'] == 'FAILED' for r in current['outbound_records']): raise RuntimeError('WMS stock posting failed; inspect original retained receipt')
                time.sleep(1)
            assert current['shipped_quantity'] == phase * 3 and current['packaged_quantity'] == phase * 3
            assert current['status'] == ('PART_SHIPPED' if phase == 1 else 'SHIPPED')
            assert len(current['packages']) == len(current['outbound_records']) == phase
            request(ship_path + '/query-warehouse', token=credential, method='POST')
            for _ in range(45):
                report = request(ship_path + '/platform-shipping', token=credential)['data']
                if len(report['tasks']) == phase and all(t['state'] == 'SUCCESS' for t in report['tasks']): break
                time.sleep(1)
            assert len(report['tasks']) == phase and all(t['state'] == 'SUCCESS' and t['quantity'] == 3 and t['attempts'] == 1 for t in report['tasks'])
            assert sum(t['quantity'] for t in report['tasks']) == phase * 3
            expected_goods = {line['platform_oid']: int(line['quantity']) // 2 for line in case['plans']}
            assert all(t['snapshot']['goods'] == expected_goods for t in report['tasks'])
            issues = request(order_path + '/issues/recheck', token=credential, method='POST')['data']['issues']
            active = [i for i in issues if i['state'] != 'RESOLVED']
            assert not any(i['category'] in ['SHORTAGE', 'LEDGER', 'PLATFORM'] for i in active)
            if phase == 1:
                quota = next(i for i in active if i['category'] == 'QUOTA')
                assert quota['evidence']['active_public'] == quota['evidence']['excess_quantity'] == 1
            else:
                assert not active
            stock = stock_snapshot(case)
            quantities(case['before_inventory'], stock, phase)
            # Replay the same signed cumulative receipt and both queue enrollments.
            send(stage, connection, secret)
            request(ship_path + '/reconcile-receipts', token=credential, method='POST')
            request(ship_path + '/platform-shipping', token=credential, method='POST')
            replay = request(ship_path, token=credential)['data']
            assert replay['shipped_quantity'] == phase * 3 and len(replay['packages']) == len(replay['outbound_records']) == phase
            assert stock_snapshot(case) == stock, 'Replay changed inventory'
            capture(credential, case, phase, output)
            stage['inventory_after'] = stock
            stage['result'] = {'phase': phase, 'actual_shipped': phase * 3, 'packages': phase, 'simulated_platform_quantity': phase * 3, 'tasks': phase, 'each_task_quantity': 3, 'each_task_attempts': 1, 'quota_warning_remaining': 1 if phase == 1 else 0, 'replay_stock_unchanged': True, 'automatic_task_generation': True}
            save(path, case)
            print(json.dumps(stage['result'], ensure_ascii=False), flush=True)
        final = request(order_path, token=credential)['data']
        assert final['business_status'] == 'SHIPPED' and final['logical_inventory_status'] == 'CONSUMED' and final['warehouse_inventory_status'] == 'OUTBOUND'
        result = {'order_sn': case['order_sn'], 'shipment_sn': case['shipment_sn'], 'status': final['business_status'], 'shipped_quantity': 6, 'packages': 2, 'platform_simulation_tasks': 2, 'simulated_platform_quantity': 6, 'stock_consumed': SKUS, 'partial_and_full_stages_passed': True, 'duplicate_stock_deduction': False, 'real_platform_notification_sent': False}
        (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
        print(json.dumps(result, ensure_ascii=False))
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
