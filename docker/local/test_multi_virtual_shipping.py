"""Retained local acceptance: one SKU, two virtual warehouses, two WMS orders, three parcels.

Seeds only isolated test masters and a mock platform source document. Inventory is
received and consumed through the normal application APIs. All receipt identities
are durable so an interrupted run can continue without another stock deduction.
"""
import hashlib
import json
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from simulate_qimen_delivery import send, mark_simulated_delivery
from test_platform_shipping_order import save

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parents[1] / 'outputs/multi-virtual-shipping'
MANIFEST = OUTPUT / 'case.json'


def quote(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "\\'") + "'"


def sql(statement, database):
    script = "import sys\nsys.path.insert(0,'/mnt/d/Users/yueguang/OmsSaaSCloud/docker/local')\nfrom local_db import execute\nprint(execute(" + repr(statement) + ',' + repr(database) + "))"
    result = subprocess.run(['wsl.exe', '--', 'python3', '-'], input=script.encode('utf8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Scoped acceptance database operation failed')
    return result.stdout.decode('utf8').strip()


def api(path, credential, payload=None, post=False):
    return request(path, payload=payload, token=credential, method='POST' if post else None)


def wait_for(get, valid, message, seconds=90):
    end = time.monotonic() + seconds
    while True:
        value = get()
        if valid(value):
            return value
        if time.monotonic() >= end:
            raise RuntimeError(message + '; original identities are retained, do not recreate the document')
        time.sleep(1)


def stock(case):
    sku, channel, tid = quote(case['sku']), case['channel_id'], quote(case['platform_tid'])
    queries = {
        'company': "SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn=" + sku,
        'channel': f"SELECT sku_sn,available_stock,allocated_stock,reserved_stock,quota_stock,quota_reserved_stock,version FROM oms_channel_inventory WHERE company_code='QM' AND channel_id={channel} AND sku_sn=" + sku,
        'warehouse': "SELECT store_code,zp_actual_number,zp_available_number,zp_lock_number,version FROM wms_inventory WHERE company_code='QM' AND sku_sn=" + sku + ' ORDER BY store_code',
        'batch': "SELECT id,store_code,zp_actual_number,zp_available_number,zp_lock_number,version FROM wms_inventory_batch WHERE company_code='QM' AND sku_sn=" + sku + ' ORDER BY id',
        'demand': f"SELECT id,demand_quantity,reserved_quantity,outbound_quantity FROM oms_order_stock_demand WHERE company_code='QM' AND channel_id={channel} AND platform_tid={tid} ORDER BY id",
        'source': f"SELECT s.id,s.quantity,s.outbound_quantity,s.reserved FROM oms_order_stock_source s JOIN oms_order_stock_demand d ON d.company_code=s.company_code AND d.id=s.demand_id WHERE d.company_code='QM' AND d.channel_id={channel} AND d.platform_tid={tid} ORDER BY s.id",
        'allocation': f"SELECT id,store_code,quantity,outbound_quantity,status,outbound_method FROM oms_order_warehouse_allocation WHERE company_code='QM' AND channel_id={channel} AND platform_tid={tid} ORDER BY id"
    }
    names = {
        'company': ['sku', 'total', 'available', 'allocated', 'reserved', 'version'],
        'channel': ['sku', 'available', 'allocated', 'reserved', 'quota', 'quota_reserved', 'version'],
        'warehouse': ['store', 'actual', 'available', 'locked', 'version'],
        'batch': ['id', 'store', 'actual', 'available', 'locked', 'version'],
        'demand': ['id', 'quantity', 'reserved', 'outbound'],
        'source': ['id', 'quantity', 'outbound', 'reserved'],
        'allocation': ['id', 'store', 'quantity', 'outbound', 'status', 'method']
    }
    result = {}
    for key, query in queries.items():
        result[key] = [dict(zip(names[key], [int(v) if v.isdigit() else v for v in line.split('\t')])) for line in sql(query, 'qm_oms_saas_inventory').splitlines()]
    return result


def verify_stock(case, snapshot, shipped):
    remaining = 10 - shipped
    assert snapshot['company'][0]['total'] == remaining and snapshot['company'][0]['reserved'] == remaining
    assert snapshot['company'][0]['available'] == snapshot['company'][0]['allocated'] == 0
    assert snapshot['channel'][0]['reserved'] == remaining
    assert sum(r['actual'] for r in snapshot['warehouse']) == remaining
    assert sum(r['locked'] for r in snapshot['warehouse']) == remaining
    assert sum(r['actual'] for r in snapshot['batch']) == remaining
    assert sum(r['locked'] for r in snapshot['batch']) == remaining
    assert all(r['available'] == 0 for r in snapshot['warehouse'] + snapshot['batch'])
    assert sum(r['outbound'] for r in snapshot['demand']) == shipped
    assert sum(r['outbound'] for r in snapshot['source']) == shipped
    assert sum(r['outbound'] for r in snapshot['allocation']) == shipped


def prepare(case, credential):
    goods_db, channel_db, order_db = 'qm_oms_saas_commodity', 'qm_oms_saas_channel', 'qm_oms_saas_order'
    if not sql('SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)=\'QM\' AND sku_sn=' + quote(case['sku']), goods_db):
        sample = api('/goods/info/list?pageNum=1&pageSize=1', credential, {}, post=True)['rows'][0]
        api('/goods/info', credential, {'skuSn': case['sku'], 'goodsSn': case['sku'], 'goodsName': '[验收测试] 多虚拟仓两单三包商品', 'categoryCode': sample['categoryCode'], 'colorCode': sample['colorCode'], 'sizeCode': sample['sizeCode'], 'marketPrice': 1, 'validity': 30})
    template = next(w for w in api('/supplychain/simulationStore/list?pageSize=100', credential)['rows'] if w['wmsSimulationCode'] == 'VC0001')
    connection = next(c for c in api('/supplychain/wmsIntegration/connections', credential)['data'] if c['id'] == template['connectionId'])
    assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
    case.update(owner_code=template['ownerCode'], real_store_code=template['realStoreCode'], connection_id=connection['id'])
    for index, warehouse in enumerate(case['warehouses'], 1):
        existing = sql('SELECT id FROM wms_simulation_store_info WHERE company_code=\'QM\' AND wms_simulation_code=' + quote(warehouse['code']), goods_db)
        if not existing:
            warehouse['id'] = api('/supplychain/simulationStore', credential, {'wmsSimulationCode': warehouse['code'], 'wmsSimulationName': '[验收测试] V' + str(index) + ' 多虚拟仓独立发货', 'ownerWarehouseId': template['ownerWarehouseId'], 'inboundMode': 2, 'outboundMode': 1, 'connectionId': connection['id'], 'externalWarehouse': template['externalWarehouse'], 'externalOwner': template['externalOwner'], 'status': 2})['data']
        else:
            warehouse['id'] = int(existing)
        detail = api('/supplychain/simulationStore/' + str(warehouse['id']), credential)['data']
        assert detail['realStoreCode'] == case['real_store_code'] and detail['ownerCode'] == case['owner_code'] and detail['connectionId'] == connection['id'] and detail['outboundMode'] == 1
        save(MANIFEST, case)
    if 'channel_id' not in case:
        existing = sql("SELECT channel_id FROM t_channel WHERE UPPER(company_code)='QM' AND channel_name=" + quote(case['channel_name']), channel_db)
        if not existing:
            api('/channel/channel', credential, {'channelName': case['channel_name'], 'channelType': 'TM', 'enabled': 1, 'toChannelEnabled': 1, 'syncEnabled': 0, 'mmodelType': 1})
            existing = sql("SELECT channel_id FROM t_channel WHERE UPPER(company_code)='QM' AND channel_name=" + quote(case['channel_name']), channel_db)
        case['channel_id'] = int(existing)
        save(MANIFEST, case)
    channel = case['channel_id']
    if not any(c['channel_id'] == channel for c in api('/order/orders/configs', credential)['data']):
        api('/order/orders/configs', credential, {'channel_id': channel, 'source_key': 'sysinfo113', 'seller_nick': case['seller'], 'enabled': 0, 'auto_convert': 0, 'review_mode': 'MANUAL', 'manual_match_review': 1, 'allowed_trade_types': ['fixed'], 'allow_public_stock': 1, 'interval_seconds': 30, 'initial_time': case['created']})
    if not case.get('configured'):
        config = api('/order/orders/allocation-config/' + str(channel), credential)['data']
        api('/order/orders/allocation-config', credential, {'channel_id': channel, 'revision': config['revision'], 'policy': {'enabled': 1, 'owner_code': case['owner_code'], 'warehouse_order': [w['code'] for w in case['warehouses']], 'province_priorities': {'北京': [case['warehouses'][0]['code']]}, 'allow_multi': 1, 'allow_partial': 1, 'allow_split_sku': 1}})
        carrier = next(c for c in api('/order/orders/carrier-configs', credential)['data'] if c['channel_id'] == channel)
        api('/order/orders/carrier-configs', credential, {'channel_id': channel, 'revision': carrier['revision'], 'policy': {'enabled': 1, 'carrier_order': ['SF'], 'routes': []}})
        config = api('/order/orders/platform-shipping-config/' + str(channel), credential)['data']
        api('/order/orders/platform-shipping-config/' + str(channel), credential, {'control_version': config['control_version'], 'mode': 'SIMULATION', 'carrier_mapping': {'SF': 'SF'}})
        case['configured'] = True
        save(MANIFEST, case)
    if not case.get('received'):
        for index, warehouse in enumerate(case['warehouses'], 1):
            api('/inventory/api/WmsInventory/addInventory?company_code=QM', credential, {'relationSn': 'TEST-MULTI-IN-' + case['case_id'] + '-V' + str(index), 'wmsInventoryBatch': {'companyCode': 'QM', 'storeCode': warehouse['code'], 'skuSn': case['sku'], 'batchCode': 'TEST-MULTI-BATCH-' + case['case_id'] + '-V' + str(index), 'zpActualNumber': warehouse['quantity'], 'zpAvailableNumber': warehouse['quantity'], 'zpLockNumber': 0, 'cpActualNumber': 0, 'cpAvailableNumber': 0, 'cpLockNumber': 0}})
        # Initial quota/scope metadata for the isolated shop/SKU only. Physical and
        # company stock quantities are created exclusively by the receive API.
        scope = json.dumps([w['code'] for w in case['warehouses']])
        sql(f"INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{channel},{quote(case['sku'])},10,{quote(scope)})", 'qm_oms_saas_inventory')
        case['received'] = True
        case['initial_stock'] = stock(case)
        assert case['initial_stock']['company'][0]['total'] == 10
        save(MANIFEST, case)
    platform = sql(f"SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id={channel} AND platform_tid={quote(case['platform_tid'])}", order_db)
    if not platform:
        trade = {'tid': case['platform_tid'], 'status': 'WAIT_SELLER_SEND_GOODS', 'type': 'fixed', 'seller_nick': case['seller'], 'created': case['created'], 'modified': case['created'], 'payment': '10.00', 'receiver_name': '多虚拟仓验收收件人', 'receiver_mobile': 'TEST-MASKED', 'receiver_state': '北京市', 'receiver_city': '北京市', 'receiver_district': '验收测试区', 'receiver_address': '验收测试地址，无实际配送', 'orders': {'order': [{'oid': case['platform_oid'], 'outer_sku_id': case['sku'], 'num': 10, 'payment': '10.00', 'title': '[验收测试] 多虚拟仓两单三包商品', 'sku_properties_name': '验收:同一SKU跨虚拟仓'}]}}
        payload = json.dumps({'trade_fullinfo_get_response': {'trade': trade}}, ensure_ascii=False)
        digest = hashlib.sha256(payload.encode()).hexdigest()
        sql(f"INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM',{channel},{quote(case['platform_tid'])},'WAIT_SELLER_SEND_GOODS','fixed',{quote(case['seller'])},{quote(case['created'])},{quote(case['created'])},{quote(case['created'])},{quote(digest)},{quote(payload)},'WAITING')", order_db)
        platform = sql(f"SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id={channel} AND platform_tid={quote(case['platform_tid'])}", order_db)
    case['platform_id'] = int(platform)
    save(MANIFEST, case)
    platform_path = '/order/orders/platform/' + str(case['platform_id'])
    current = api(platform_path, credential)['data']
    if not current.get('business_order_id') and current['conversion_status'] in ['WAITING', 'EXCEPTION']:
        api(platform_path + '/retry', credential, post=True)
    current = wait_for(lambda: api(platform_path, credential)['data'], lambda p: p.get('business_order_id') is not None, 'Conversion did not complete')
    case['order_id'] = current['business_order_id']
    order_path = '/order/orders/business/' + str(case['order_id'])
    business = wait_for(lambda: api(order_path, credential)['data'], lambda b: b['inventory_status'] == 'RESERVED' or case.get('completed'), 'Logical reservation did not complete')
    case['order_sn'] = business['order_sn']
    if business['review_status'] != 'APPROVED':
        response = api('/order/orders/business/approve-batch', credential, {'orders': [{'id': business['id'], 'control_version': business['control_version']}], 'reason': '多虚拟仓两单三包验收'})['data']
        assert response[0]['success'], response
        business = api(order_path, credential)['data']
    if business['allocation_status'] != 'FULL':
        response = api('/order/orders/business/allocate-batch', credential, {'items': [{'id': business['id'], 'control_version': business['control_version']}]})['data']
        assert response[0]['success'], response
        business = api(order_path, credential)['data']
    assert len(business['shipments']) == 2 and business['allocation_quantity'] == 10
    assert {s['store_code']: s['quantity'] for s in business['shipments']} == {w['code']: w['quantity'] for w in case['warehouses']}
    for warehouse in case['warehouses']:
        shipment = next(s for s in business['shipments'] if s['store_code'] == warehouse['code'])
        warehouse.update(shipment_id=shipment['id'], shipment_sn=shipment['shipment_sn'])
        detail = api('/order/orders/shipments/' + str(shipment['id']), credential)['data']
        if detail['wms_dispatch_state'] == 'NONE':
            assert not detail['packages'] and detail['package_count'] == detail['shipped_quantity'] == 0
            assert detail['carrier_match_status'] == 'MATCHED'
            response = api('/order/orders/shipments/dispatch', credential, {'items': [{'id': detail['id'], 'control_version': detail['control_version']}]})['data']
            assert response[0]['success'], response
        detail = wait_for(lambda: api('/order/orders/shipments/' + str(shipment['id']), credential)['data'], lambda s: s['wms_dispatch_state'] == 'ACCEPTED', 'Warehouse acceptance did not complete')
        assert detail['warehouse_target']['connection_id'] == connection['id'] and detail['warehouse_target']['environment'] == 'TEST'
        assert len(detail['dispatch_lines']) == 1 and detail['dispatch_lines'][0]['store_code'] == warehouse['code']
        warehouse.update(external_order=detail['wms_external_order'], external_owner=detail['warehouse_target']['external_owner'], external_warehouse=detail['warehouse_target']['external_warehouse'])
        warehouse['plans'] = [{**{k: line[k] for k in ['id', 'sku_sn', 'quantity', 'platform_oid', 'store_code', 'batch_code']}, 'batch_id': next(l['batch_id'] for l in detail['lines'] if l['id'] == line['id'])} for line in detail['dispatch_lines']]
        if 'callback_body' not in warehouse:
            root = ET.Element('request')
            header = ET.SubElement(root, 'deliveryOrder')
            for key, value in {'deliveryOrderCode': warehouse['shipment_sn'], 'deliveryOrderId': warehouse['external_order'], 'warehouseCode': warehouse['external_warehouse'], 'ownerCode': warehouse['external_owner'], 'status': 'DELIVERED', 'confirmType': '0', 'outBizCode': 'SIM_MULTI_' + warehouse['shipment_sn'], 'totalOrderLines': 1, 'orderConfirmTime': datetime.now().strftime('%Y-%m-%d %H:%M:%S')}.items():
                ET.SubElement(header, key).text = str(value)
            line = warehouse['plans'][0]
            element = ET.SubElement(ET.SubElement(root, 'orderLines'), 'orderLine')
            for key, value in {'orderLineNo': line['id'], 'itemCode': case['sku'], 'ownerCode': warehouse['external_owner'], 'batchCode': line['batch_code'], 'inventoryType': 'ZP', 'actualQty': warehouse['quantity']}.items():
                ET.SubElement(element, key).text = str(value)
            packages = ET.SubElement(root, 'packages')
            for index in range(1, warehouse['package_count'] + 1):
                parcel = ET.SubElement(packages, 'package')
                for key, value in {'packageCode': 'SIMPKG_' + warehouse['shipment_sn'] + '_P' + str(index), 'expressCode': 'TEST-SF-' + warehouse['shipment_sn'] + '-P' + str(index), 'logisticsCode': detail['warehouse_carrier_code'], 'logisticsName': detail['carrier_name']}.items():
                    ET.SubElement(parcel, key).text = value
                item = ET.SubElement(ET.SubElement(parcel, 'items'), 'item')
                ET.SubElement(item, 'itemCode').text = case['sku']
                ET.SubElement(item, 'quantity').text = str(warehouse['quantity'] // warehouse['package_count'])
            warehouse['callback_body'] = ET.tostring(root, encoding='unicode')
        save(MANIFEST, case)
    if not case.get('allocated_stock'):
        case['allocated_stock'] = stock(case)
        verify_stock(case, case['allocated_stock'], 0)
        save(MANIFEST, case)
    return connection


def capture(case, credential, stage):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1600, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
        page = context.new_page()
        errors, writes = [], []
        page.on('pageerror', lambda e: errors.append(str(e)))
        def readonly(route):
            if route.request.method == 'GET':
                route.continue_()
            else:
                writes.append(route.request.url.split('?')[0]); route.abort()
        page.route('**/prod-api/**', readonly)
        page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(case['order_id']), wait_until='networkidle')
        drawer = page.locator('.el-drawer:visible')
        drawer.get_by_role('tab', name='分仓明细', exact=True).click()
        groups = drawer.locator('.allocation-warehouse')
        expect(groups).to_have_count(2)
        expect(groups.first).to_contain_text(case['warehouses'][0]['code'])
        expect(groups.nth(1)).to_contain_text(case['warehouses'][1]['code'])
        page.screenshot(path=str(OUTPUT / (stage + '-allocation.png')))
        drawer.get_by_role('tab', name='发货单', exact=True).click()
        groups = drawer.locator('.order-warehouse-group')
        expect(groups).to_have_count(2)
        groups.first.scroll_into_view_if_needed()
        expect(groups.first).to_contain_text('2 个包裹')
        expect(groups.nth(1)).to_contain_text('1 个包裹' if stage == 'full' else '包裹待回传 / 登记')
        page.screenshot(path=str(OUTPUT / (stage + '-shipments.png')))
        for index, warehouse in enumerate(case['warehouses']):
            group = groups.nth(index)
            group.get_by_role('button', name='查看本仓包裹', exact=True).click()
            parcels = page.locator('.shipment-packages-dialog .el-dialog:visible')
            expect(parcels.locator('.package-card')).to_have_count(warehouse['package_count'] if stage == 'full' or index == 0 else 0)
            expect(parcels).to_contain_text(warehouse['code'])
            page.screenshot(path=str(OUTPUT / (stage + '-V' + str(index + 1) + '-packages.png')))
            parcels.get_by_role('button', name='关闭', exact=True).click()
        assert not errors and not writes, (errors, writes)
        browser.close()


def run(case, credential, connection):
    secret = next(c for c in json.loads((HERE / 'wms-simulator-credentials.json').read_text(encoding='utf8')) if c['app_key'] == connection['app_key'] and c['customer_id'] == connection['customer_id'])
    order_path = '/order/orders/business/' + str(case['order_id'])
    for phase, warehouse in enumerate(case['warehouses'], 1):
        if phase == 1 and case.get('completed'):
            continue
        shipment_path = '/order/orders/shipments/' + str(warehouse['shipment_id'])
        mark_simulated_delivery(warehouse, connection)
        send(warehouse, connection, secret)
        api(shipment_path + '/reconcile-receipts', credential, post=True)
        detail = wait_for(lambda: api(shipment_path, credential)['data'], lambda s: s['shipped_quantity'] == warehouse['quantity'], 'Outbound stock posting did not complete')
        assert detail['status'] == 'SHIPPED' and detail['package_count'] == warehouse['package_count'] and detail['packaged_quantity'] == warehouse['quantity']
        assert len(detail['outbound_records']) == 1 and detail['outbound_records'][0]['state'] == 'COMPLETED'
        report = wait_for(lambda: api(shipment_path + '/platform-shipping', credential)['data'], lambda r: len(r['tasks']) == warehouse['package_count'] and all(t['state'] == 'SUCCESS' for t in r['tasks']), 'Automatic simulated platform tasks did not complete')
        assert all(t['quantity'] == warehouse['quantity'] // warehouse['package_count'] and t['attempts'] == 1 and t['snapshot']['goods'] == {case['platform_oid']: warehouse['quantity'] // warehouse['package_count']} for t in report['tasks'])
        api(shipment_path + '/query-warehouse', credential, post=True)
        business = api(order_path, credential)['data']
        expected = 6 if phase == 1 else 10
        assert business['shipped_quantity'] == expected and business['business_status'] == ('PART_SHIPPED' if phase == 1 else 'SHIPPED')
        snapshot = stock(case)
        verify_stock(case, snapshot, expected)
        if phase == 1:
            unshipped = api('/order/orders/shipments/' + str(case['warehouses'][1]['shipment_id']), credential)['data']
            assert unshipped['shipped_quantity'] == unshipped['package_count'] == 0 and not unshipped['outbound_records']
            assert next(s for s in snapshot['warehouse'] if s['store'] == case['warehouses'][1]['code'])['actual'] == 4
        send(warehouse, connection, secret)
        api(shipment_path + '/reconcile-receipts', credential, post=True)
        api(shipment_path + '/platform-shipping', credential, post=True)
        assert stock(case) == snapshot, 'Replay changed stock quantities or versions'
        repeated = api(shipment_path, credential)['data']
        assert repeated['package_count'] == warehouse['package_count'] and len(repeated['outbound_records']) == 1
        repeated_report = api(shipment_path + '/platform-shipping', credential)['data']
        assert len(repeated_report['tasks']) == warehouse['package_count'] and all(t['attempts'] == 1 for t in repeated_report['tasks'])
        warehouse['result'] = {'shipped': warehouse['quantity'], 'packages': warehouse['package_count'], 'platform_tasks': warehouse['package_count'], 'quantity_per_task': warehouse['quantity'] // warehouse['package_count'], 'single_outbound_record': True, 'replay_stock_unchanged': True, 'stock_after': snapshot}
        save(MANIFEST, case)
        capture(case, credential, 'partial' if phase == 1 else 'full')
        print(json.dumps({'phase': phase, 'business_status': business['business_status'], 'order_shipped': expected, 'virtual_warehouse': warehouse['code'], 'packages': warehouse['package_count'], 'replay_stock_unchanged': True}, ensure_ascii=False), flush=True)
    ids = ','.join(str(w['shipment_id']) for w in case['warehouses'])
    sales_query = "SELECT d.shipment_id,d.store_code,d.ticket_id,t.sn,(SELECT SUM(g.number_expected) FROM wms_tickets_goods g WHERE g.company_code=t.company_code AND g.sn=t.sn),(SELECT SUM(g.number_actually) FROM wms_tickets_goods g WHERE g.company_code=t.company_code AND g.sn=t.sn),(SELECT COUNT(*) FROM wms_sales_outbound_movement m WHERE m.company_code=d.company_code AND m.ticket_id=d.ticket_id) FROM wms_sales_outbound_document d JOIN wms_tickets t ON t.id=d.ticket_id AND t.company_code=d.company_code WHERE d.company_code='QM' AND d.shipment_id IN (" + ids + ') ORDER BY d.shipment_id'
    sales_rows = wait_for(lambda: sql(sales_query, 'qm_oms_saas_commodity').splitlines(), lambda rows: len(rows) == 2 and sum(int(r.split('\t')[5]) for r in rows) == 10, 'Sales outbound document projection did not complete')
    case['sales_documents'] = [dict(zip(['shipment_id', 'store_code', 'ticket_id', 'document_sn', 'planned', 'actual', 'movement_count'], row.split('\t'))) for row in sales_rows]
    assert all(int(d['planned']) == int(d['actual']) and int(d['movement_count']) == 1 and d['document_sn'].startswith('XS') for d in case['sales_documents'])
    issues = api(order_path + '/issues/recheck', credential, post=True)['data']['issues']
    assert not [i for i in issues if i['state'] != 'RESOLVED']
    final = api(order_path, credential)['data']
    assert final['business_status'] == 'SHIPPED' and final['logical_inventory_status'] == 'CONSUMED' and final['warehouse_inventory_status'] == 'OUTBOUND'
    assert len(final['shipments']) == 2 and sum(s['package_count'] for s in final['shipments']) == 3
    assert api('/order/orders/platform/' + str(case['platform_id']), credential)['data']['platform_status'] == 'WAIT_SELLER_SEND_GOODS'
    case['completed'] = True
    case['business_url'] = BASE + '/oms-order/business?view=business&orderId=' + str(case['order_id'])
    save(MANIFEST, case)
    result = {'order_sn': case['order_sn'], 'order_id': case['order_id'], 'platform_tid': case['platform_tid'], 'sku': case['sku'], 'business_url': case['business_url'], 'physical_warehouse': case['real_store_code'], 'warehouse_shipments': [{k: w[k] for k in ['code', 'quantity', 'shipment_sn', 'package_count']} for w in case['warehouses']], 'sales_documents': case['sales_documents'], 'final_status': 'SHIPPED', 'shipped_quantity': 10, 'packages': 3, 'platform_simulation_tasks': 3, 'platform_simulated_quantity': 10, 'duplicate_stock_deduction': False, 'real_platform_notification_sent': False, 'historical_documents_reallocated': False}
    (OUTPUT / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(result, ensure_ascii=False), flush=True)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if MANIFEST.exists():
        case = json.loads(MANIFEST.read_text(encoding='utf8'))
    else:
        stamp = datetime.now().strftime('%Y%m%d%H%M%S')
        tid = '991' + stamp
        case = {'case_id': stamp, 'created': datetime.now().strftime('%Y-%m-%d %H:%M:%S'), 'sku': 'ACCEPT_MULTI_' + stamp, 'channel_name': '[验收测试] 多虚拟仓两单三包 ' + stamp, 'seller': 'ACCEPT_MULTI_' + stamp, 'platform_tid': tid, 'platform_oid': tid + '01', 'warehouses': [{'code': 'ACCEPT_V1_' + stamp, 'quantity': 6, 'package_count': 2}, {'code': 'ACCEPT_V2_' + stamp, 'quantity': 4, 'package_count': 1}]}
        save(MANIFEST, case)
    assert case['sku'].startswith('ACCEPT_MULTI_') and case['platform_tid'].startswith('991')
    credential = login()
    try:
        connection = prepare(case, credential)
        print(json.dumps({'order_sn': case['order_sn'], 'order_id': case['order_id'], 'prepared': True, 'virtual_quantities': [6, 4], 'same_physical_warehouse': True, 'warehouse_shipments': 2}, ensure_ascii=False), flush=True)
        run(case, credential, connection)
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
