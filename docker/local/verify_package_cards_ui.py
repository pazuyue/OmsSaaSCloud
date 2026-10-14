"""Read-only retained document acceptance plus intercepted multi-warehouse parcel scenarios."""
import copy
import json
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE

SN = 'XS870a08cfdade45b5be4ad60d3cc802ab'
OUTPUT = Path(__file__).resolve().parents[2] / 'outputs/package-ui'


def signature(row):
    return {'shipped': row['shipped_quantity'], 'packaged': row['packaged_quantity'], 'packages': row['packages'], 'outbounds': row['outbound_records']}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    token = login()
    errors, writes, failed_apis = [], [], []
    try:
        live = request('/order/orders/shipments/3', token=token)['data']
        business = request('/order/orders/business/28', token=token)['data']
        assert live['shipped_quantity'] == 6 and len(live['packages']) == 1
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100}, permissions=['clipboard-read', 'clipboard-write'])
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))

            def readonly(route):
                if route.request.method == 'GET':
                    route.continue_()
                else:
                    writes.append(urlparse(route.request.url).path)
                    route.abort()

            def check_response(response):
                if '/prod-api/order/orders/' in response.url:
                    try:
                        data = response.json()
                        if data.get('code', 200) != 200:
                            failed_apis.append({'path': urlparse(response.url).path, 'message': data.get('msg')})
                    except Exception:
                        pass

            page.route('**/prod-api/**', readonly)
            page.on('response', check_response)
            page.goto(BASE + '/oms-order/shipments?number=' + SN, wait_until='networkidle')
            page.get_by_role('button', name=SN, exact=True).first.click()
            drawer = page.locator('.el-drawer:visible')
            cards = drawer.locator('.shipment-package-cards')
            expect(cards.locator('.package-card')).to_have_count(1)
            expect(cards.get_by_text('模拟运单', exact=True)).to_be_visible()
            expect(cards.locator('.package-products').get_by_text('test01', exact=True)).to_be_visible()
            expect(cards.get_by_text('× 4 件', exact=True)).to_be_visible()
            expect(cards.get_by_text('× 2 件', exact=True)).to_be_visible()
            expect(cards.locator('.package-waybill')).to_contain_text(live['packages'][0]['express_code'])
            cards.get_by_role('button', name='复制运单号 ' + live['packages'][0]['express_code'], exact=True).click()
            page.wait_for_timeout(150)
            assert page.evaluate('navigator.clipboard.readText()') == live['packages'][0]['express_code']
            cards.locator('.package-sources .el-collapse-item__header').click()
            expect(cards).to_contain_text('9007664503219726110')
            drawer.locator('.el-drawer__body').evaluate('(element) => element.scrollTop = 0')
            page.wait_for_timeout(200)
            page.screenshot(path=str(OUTPUT / 'retained-shipment-packages.png'))
            cards.locator('.package-sources .el-collapse-item__header').click()
            page.set_viewport_size({'width': 390, 'height': 844})
            page.wait_for_timeout(250)
            drawer.locator('.el-drawer__body').evaluate('(element) => element.scrollTop = 0')
            page.wait_for_timeout(200)
            waybill = cards.locator('.waybill-number').bounding_box()
            assert waybill['x'] >= 0 and waybill['x'] + waybill['width'] <= 391, waybill
            page.screenshot(path=str(OUTPUT / 'retained-shipment-mobile.png'))
            page.set_viewport_size({'width': 1600, 'height': 1100})
            drawer.locator('.footer').get_by_role('button', name='关闭', exact=True).click()

            # Opening the query preview reads current parcels without submitting a warehouse query.
            page.locator('.shipment-workspace .el-table__fixed-right').get_by_role('button', name='核对仓库结果', exact=True).first.click()
            dispatch = page.locator('.shipment-dispatch-dialog .el-dialog:visible')
            expect(dispatch.locator('.shipment-package-cards .package-card')).to_have_count(1)
            expect(dispatch.locator('.shipment-package-cards .package-products').get_by_text('test01', exact=True)).to_be_visible()
            page.screenshot(path=str(OUTPUT / 'warehouse-query-packages.png'))
            dispatch.get_by_role('button', name='取消', exact=True).click()

            page.goto(BASE + '/oms-order/business?view=business&orderId=28', wait_until='networkidle')
            order_drawer = page.locator('.el-drawer:visible')
            order_drawer.get_by_role('tab', name='分仓明细', exact=True).click()
            allocation = order_drawer.locator('.allocation-fulfillment')
            expect(allocation).to_contain_text('青木WMS001')
            expect(allocation.get_by_role('button', name='查看包裹（1 个）', exact=True)).to_be_visible()
            allocation.get_by_role('button', name='查看包裹（1 个）', exact=True).click()
            parcels = page.locator('.shipment-packages-dialog .el-dialog:visible')
            expect(parcels.locator('.package-card')).to_have_count(1)
            expect(parcels).to_contain_text(live['packages'][0]['express_code'])
            page.wait_for_timeout(350)
            page.screenshot(path=str(OUTPUT / 'allocation-package-entry.png'))
            parcels.get_by_role('button', name='关闭', exact=True).click()
            order_drawer.get_by_role('tab', name='发货单', exact=True).click()
            shipments = order_drawer.locator('.order-shipments')
            expect(shipments.locator('.order-warehouse-group')).to_have_count(1)
            shipments.get_by_role('button', name='查看本仓包裹', exact=True).click()
            expect(parcels.locator('.package-card')).to_have_count(1)
            page.wait_for_timeout(350)
            page.screenshot(path=str(OUTPUT / 'business-order-package-entry.png'))
            parcels.get_by_role('button', name='关闭', exact=True).click()

            # Two parcels in warehouse A, the same SKU split across source lines/parcels;
            # Virtual warehouse B shares the physical warehouse but has its own unshipped document. All data below is intercepted.
            fixture_order = copy.deepcopy(business)
            first = copy.deepcopy(live)
            first.update(id=9900801, shipment_sn='XS_PARCEL_UI_A', order_id=9900800, order_sn='SO_PARCEL_UI', store_code='UI_V1', real_store_code='UI_W1', real_store_name='验收华东仓', quantity=8, shipped_quantity=6, packaged_quantity=4, unpacked_quantity=2, package_count=2, status='PART_SHIPPED', platform_shipping_state='NONE', platform_shipping_success_quantity=0, wms_progress_message='仓库状态：PARTDELIVERED；实际出库 6 / 8 件，剩余 2 件', wms_warehouse_status='PARTDELIVERED', can_register_package=False, can_dispatch=False, fulfillment_block='', logs=[], outbound_records=[])
            source_line = lambda ident, sku, qty, batch: dict(shipment_line_id=ident, sku_sn=sku, goods_name='验收商品 ' + sku, platform_oid='9007664503219726110', store_code='UI_V1', batch_id=batch, quantity=qty)
            first['packages'] = [dict(id=9900811, package_code='UI_P1', express_code='UI-WAYBILL-1', carrier_name='顺丰快递', source_mode='WMS', quantity=2, outbound_time='2026-10-13 16:00:00', lines=[source_line(1, 'SKU-A', 1, 1), source_line(2, 'SKU-A', 1, 2)]), dict(id=9900812, package_code='UI_P2', express_code='UI-WAYBILL-2', carrier_name='顺丰快递', source_mode='WMS', quantity=2, outbound_time='2026-10-13 16:10:00', lines=[source_line(1, 'SKU-A', 1, 1), source_line(3, 'SKU-B', 1, 3)])]
            second = copy.deepcopy(first)
            second.update(id=9900802, shipment_sn='XS_PARCEL_UI_B', store_code='UI_V2', real_store_code='UI_W1', real_store_name='验收华东仓', quantity=4, shipped_quantity=0, packaged_quantity=0, unpacked_quantity=0, package_count=0, packages=[], status='WAREHOUSE_PROCESSING', wms_progress_message='仓库已接收，等待实际出库回传', wms_warehouse_status='ACCEPT')
            fixture_order.update(id=9900800, order_sn='SO_PARCEL_UI', demand_quantity=12, allocation_quantity=12, shipment_count=2, shipment_quantity=12, shipped_quantity=6, warehouse_accepted_quantity=12, shipments=[first, second], logs=[], warehouse_allocations=[dict(store_code=row['store_code'], real_store_code=row['real_store_code'], real_store_name=row['real_store_name'], owner_code=row['owner_code'], quantity=row['quantity']) for row in [first, second]])

            def fixtures(route):
                path = urlparse(route.request.url).path
                if path.endswith('/business/9900800'):
                    data = fixture_order
                elif path.endswith('/shipments/9900801'):
                    data = first
                elif path.endswith('/shipments/9900802'):
                    data = second
                else:
                    route.fallback()
                    return
                route.fulfill(status=200, content_type='application/json', body=json.dumps({'code': 200, 'data': data}, ensure_ascii=False))

            page.route('**/prod-api/order/orders/**', fixtures)
            page.goto(BASE + '/oms-order/business?view=business&orderId=9900800', wait_until='networkidle')
            order_drawer = page.locator('.el-drawer:visible')
            order_drawer.get_by_role('tab', name='分仓明细', exact=True).click()
            allocation = order_drawer.locator('.allocation-fulfillment')
            expect(allocation.locator('.allocation-warehouse')).to_have_count(2)
            expect(allocation.locator('.allocation-warehouse').first).to_contain_text('虚拟仓 UI_V1')
            expect(allocation.locator('.allocation-warehouse').nth(1)).to_contain_text('虚拟仓 UI_V2')
            expect(allocation.locator('.allocation-warehouse').nth(1)).to_contain_text('包裹待回传 / 登记')
            allocation.get_by_role('button', name='查看包裹（2 个）', exact=True).click()
            expect(parcels.locator('.package-card')).to_have_count(2)
            expect(parcels).to_contain_text('剩余待出库 2 件')
            expect(parcels).to_contain_text('已出库待关联包裹 2 件')
            expect(parcels.locator('.package-card').first.locator('.package-products li')).to_have_count(1)
            expect(parcels.locator('.package-card').first).to_contain_text('× 2 件')
            assert not parcels.get_by_text('模拟运单', exact=True).count()
            parcels.locator('.package-card').first.locator('.el-collapse-item__header').click()
            expect(parcels.locator('.package-card').first.locator('.el-table__body-wrapper tr')).to_have_count(2)
            page.screenshot(path=str(OUTPUT / 'multiple-parcels-source-lines.png'))
            parcels.get_by_role('button', name='关闭', exact=True).click()
            allocation.get_by_role('button', name='包裹待回传 / 登记', exact=True).click()
            expect(parcels.locator('.package-card')).to_have_count(0)
            expect(parcels.locator('.package-empty')).to_contain_text('尚无包裹')
            expect(parcels).to_contain_text('剩余待出库 4 件')
            parcels.get_by_role('button', name='关闭', exact=True).click()
            order_drawer.get_by_role('tab', name='发货单', exact=True).click()
            expect(order_drawer.locator('.order-warehouse-group')).to_have_count(2)
            order_drawer.locator('.order-shipments').scroll_into_view_if_needed()
            page.screenshot(path=str(OUTPUT / 'multi-warehouse-summary.png'))

            # A new parcel arriving while the dialog is open refreshes the parent warehouse totals.
            group = order_drawer.locator('.order-warehouse-group').first
            group.get_by_role('button', name='查看本仓包裹', exact=True).click()
            first['packages'].append(dict(id=9900813, package_code='UI_P3', express_code='UI-WAYBILL-3', carrier_name='顺丰快递', source_mode='WMS', quantity=2, outbound_time='2026-10-13 16:20:00', lines=[source_line(3, 'SKU-B', 2, 3)]))
            first.update(package_count=3,packaged_quantity=6,unpacked_quantity=0)
            parcels.get_by_role('button', name='刷新包裹', exact=True).click()
            expect(parcels.locator('.package-card')).to_have_count(3)
            expect(group.locator('.order-warehouse-heading')).to_contain_text('3 个包裹')
            parcels.get_by_role('button', name='关闭', exact=True).click()

            # A failed read must show an error rather than zero parcels, and refresh can recover.
            page.route('**/prod-api/order/orders/shipments/9900801', lambda route: route.abort('failed'))
            order_drawer.locator('.order-warehouse-group').first.get_by_role('button', name='查看本仓包裹', exact=True).click()
            expect(parcels.locator('.load-error')).to_be_visible()
            expect(parcels.locator('.package-empty')).to_have_count(0)
            page.unroute('**/prod-api/order/orders/shipments/9900801')
            parcels.get_by_role('button', name='刷新包裹', exact=True).click()
            expect(parcels.locator('.package-card')).to_have_count(3)
            assert not errors and not writes and not failed_apis, (errors, writes, failed_apis)
            browser.close()

        after = request('/order/orders/shipments/3', token=token)['data']
        assert signature(live) == signature(after), 'UI must not change the retained document'
        OUTPUT.joinpath('verification.json').write_text(json.dumps({'retained_shipment': SN, 'live_parcel_count': 1, 'live_quantity': 6, 'entries': ['allocation', 'business', 'shipment', 'warehouse_query'], 'copy_and_mobile': True, 'multiple_warehouses_and_parcels': True, 'same_physical_different_virtual_separate_groups': True, 'same_sku_source_lines_preserved': True, 'empty_and_failed_reads_distinct': True, 'read_only_browser': True, 'retained_document_unchanged': True, 'parent_summary_refreshes_with_parcels': True}, ensure_ascii=False, indent=2), encoding='utf8')
        print('PASS parcel cards: four live entries, SKU quantities, clipboard, mobile, multi-warehouse/split parcels, empty/error recovery; GET-only')
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
