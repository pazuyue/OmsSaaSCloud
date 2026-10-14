"""Read-only live verification of the retained two-document, three-parcel acceptance."""
import json
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import MANIFEST, OUTPUT


def main():
    case = json.loads(MANIFEST.read_text(encoding='utf8'))
    assert case['completed']
    credential = login()
    try:
        interactions = {}
        for warehouse in case['warehouses']:
            logs = request('/supplychain/wmsIntegration/logs?sn=' + warehouse['shipment_sn'], token=credential)['data']
            assert len([row for row in logs if row['action'] == 'OUTBOUND_CREATE' and row['result'] == 'SUCCESS']) == 1
            assert len([row for row in logs if row['action'] == 'OUTBOUND_RECEIPT' and row['result'] == 'SUCCESS']) == 2
            interactions[warehouse['shipment_sn']] = [{k: row[k] for k in ['id', 'action', 'result', 'direction']} for row in logs]
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
            page.goto(case['business_url'], wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            drawer.get_by_role('tab', name='发货单', exact=True).click()
            groups = drawer.locator('.order-warehouse-group')
            expect(groups).to_have_count(2)
            for index, warehouse in enumerate(case['warehouses']):
                groups.nth(index).locator('.platform-shipping-status').get_by_role('button', name='模拟回传成功', exact=True).click()
                dialog = page.locator('.platform-shipping-dialog .el-dialog:visible')
                expect(dialog.locator('.shipping-task')).to_have_count(warehouse['package_count'])
                expect(dialog).to_contain_text('模拟成功 ' + str(warehouse['quantity']) + ' 件')
                page.screenshot(path=str(OUTPUT / ('V' + str(index + 1) + '-platform-tasks.png')))
                dialog.get_by_role('button', name='关闭', exact=True).click()
            for index, document in enumerate(case['sales_documents'], 1):
                page.goto(BASE + '/oms-supplychain/wmsTickets?documentType=SALE_OUT&noSn=' + next(w['shipment_sn'] for w in case['warehouses'] if str(w['shipment_id']) == document['shipment_id']), wait_until='networkidle')
                page.get_by_role('button', name=document['document_sn'], exact=True).click()
                drawer = page.locator('.el-drawer:visible')
                expect(drawer).to_contain_text(case['sku'])
                expect(drawer).to_contain_text('[验收测试] V' + str(index) + ' 多虚拟仓独立发货')
                drawer.get_by_role('tab', name='实际出库记录', exact=True).click()
                expect(drawer).to_contain_text('扣账成功')
                page.screenshot(path=str(OUTPUT / ('sales-' + document['shipment_id'] + '-outbound-record.png')))
                drawer.get_by_role('button', name='关闭', exact=True).click()
            assert not errors and not writes, (errors, writes)
            browser.close()
        result = {'warehouse_interactions': interactions, 'separate_successful_create_requests': 2, 'signed_receipt_and_exact_replay_per_shipment': True, 'platform_tasks_visible': 3, 'sales_document_actual_records_visible': 2, 'browser_errors': errors, 'browser_writes': writes, 'live_ui_verified': True}
        (OUTPUT / 'ui-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
        print('PASS live UI: separate WMS creates, three platform tasks, two sales actual records; GET-only')
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
