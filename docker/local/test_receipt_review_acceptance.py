"""Explicit retained local acceptance: signed refusal -> human review -> original receipt stock posting.

Only the dedicated review case is created. The warehouse never corrects/resends
the refused body to make approval succeed; the UI confirms the saved signed body.
"""
import copy
import json
import time
from datetime import datetime
from playwright.sync_api import sync_playwright, expect
import test_multi_virtual_shipping as multi
from test_platform_shipping_order import save
from simulate_qimen_delivery import send, mark_simulated_delivery
from verify_goods_workspace_ui import login, request, BASE

OUTPUT = multi.HERE.parents[1] / 'outputs/receipt-review'
MANIFEST = OUTPUT / 'case.json'


def browser_confirmation(case, credential, review):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1600, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
        page = context.new_page()
        errors, writes = [], []
        page.on('pageerror', lambda e: errors.append(str(e)))
        def scoped(route):
            if route.request.method == 'GET':
                route.continue_()
            elif route.request.method == 'POST' and route.request.url.split('?')[0].endswith('/receipt-reviews/' + str(review['id'])):
                writes.append(route.request.post_data_json)
                route.continue_()
            else:
                route.abort()
                raise AssertionError('Unexpected UI write')
        page.route('**/prod-api/**', scoped)
        second = case['warehouses'][1]
        page.goto(BASE + '/oms-order/shipments?number=' + second['shipment_sn'], wait_until='networkidle')
        page.get_by_role('button', name=second['shipment_sn'], exact=True).first.click()
        drawer = page.locator('.el-drawer:visible')
        card = drawer.locator('.receipt-reviews .review-card').first
        card.scroll_into_view_if_needed()
        expect(card).to_contain_text('待人工核对')
        expect(card).to_contain_text('本次未扣账')
        expect(card).to_contain_text(case['warehouses'][0]['shipment_sn'])
        confirm = card.get_by_role('button', name='确认共用运单并补记出库', exact=True)
        expect(confirm).to_be_disabled()
        page.screenshot(path=str(OUTPUT / 'pending-unposted.png'))
        card.get_by_placeholder('填写核对依据和处理原因（必填）').fill('验收核对：仓库已实际出库4件，与同一业务订单V1包裹共用运单，确认接纳原签名回传。')
        card.locator('.el-checkbox').click()
        expect(confirm).to_be_enabled()
        confirm.click()
        expect(card).to_contain_text('已确认处理', timeout=45000)
        expect(card).to_contain_text('本次已扣账', timeout=45000)
        expect(card).to_contain_text('admin')
        card.scroll_into_view_if_needed()
        page.screenshot(path=str(OUTPUT / 'confirmed-posted.png'))
        expect(drawer.locator('.shipment-package-cards')).to_contain_text('已核对共用运单')
        page.screenshot(path=str(OUTPUT / 'confirmed-package.png'))
        assert len(writes) == 1 and not errors, (writes, errors)
        browser.close()
        return {'browser_errors': errors, 'confirmation_requests': len(writes), 'required_reason_and_checkbox': True}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    multi.OUTPUT, multi.MANIFEST = OUTPUT, MANIFEST
    if MANIFEST.exists():
        case = json.loads(MANIFEST.read_text(encoding='utf8'))
    else:
        stamp = datetime.now().strftime('%Y%m%d%H%M%S')
        tid = '992' + stamp
        case = {'case_id': stamp, 'created': datetime.now().strftime('%Y-%m-%d %H:%M:%S'), 'sku': 'ACCEPT_MULTI_' + stamp, 'channel_name': '[验收测试] 拒绝回传人工核对 ' + stamp, 'seller': 'ACCEPT_REVIEW_' + stamp, 'platform_tid': tid, 'platform_oid': tid + '01', 'warehouses': [{'code': 'REVIEW_V1_' + stamp, 'quantity': 6, 'package_count': 2}, {'code': 'REVIEW_V2_' + stamp, 'quantity': 4, 'package_count': 1}]}
        save(MANIFEST, case)
    credential = login()
    try:
        connection = multi.prepare(case, credential)
        secret = next(c for c in json.loads((multi.HERE / 'wms-simulator-credentials.json').read_text(encoding='utf8')) if c['app_key'] == connection['app_key'] and c['customer_id'] == connection['customer_id'])
        first, second = case['warehouses']
        path = '/order/orders/shipments/' + str(second['shipment_id'])
        first_path = '/order/orders/shipments/' + str(first['shipment_id'])
        if not case.get('first_posted'):
            mark_simulated_delivery(first, connection); send(first, connection, secret)
            multi.api(first_path + '/reconcile-receipts', credential, post=True)
            multi.wait_for(lambda: multi.api(first_path, credential)['data'], lambda s: s['shipped_quantity'] == 6, 'First warehouse posting did not complete')
            case['first_posted'] = True
            case['before_rejection'] = multi.stock(case)
            multi.verify_stock(case, case['before_rejection'], 6)
            # Keep package code, quantities, SKU, batch and signed source message unchanged;
            # use the first shipment's actual waybill to deliberately trigger a conflict.
            shared = 'TEST-SF-' + first['shipment_sn'] + '-P1'
            second['callback_body'] = second['callback_body'].replace('TEST-SF-' + second['shipment_sn'] + '-P1', shared)
            case['shared_waybill'] = shared
            save(MANIFEST, case)
        report = multi.api(path + '/receipt-reviews', credential)['data']
        if not report['reviews']:
            mark_simulated_delivery(second, connection)
            try:
                send(second, connection, secret)
            except AssertionError as e:
                assert '待人工核对' in str(e), str(e)
            else:
                raise AssertionError('Conflicting callback must be refused before stock posting')
            report = multi.api(path + '/receipt-reviews', credential)['data']
        assert len(report['reviews']) == 1
        review = report['reviews'][0]
        case['review_id'] = review['id']; save(MANIFEST, case)
        if review['state'] == 'WAIT_REVIEW':
            snapshot = multi.stock(case)
            assert snapshot == case['before_rejection'], 'Refused callback changed stock'
            business = multi.api('/order/orders/business/' + str(case['order_id']), credential)['data']
            assert business['business_status'] == 'PART_SHIPPED' and business['shipped_quantity'] == 6
            detail = multi.api(path, credential)['data']
            assert detail['shipped_quantity'] == detail['package_count'] == 0 and not detail['outbound_records']
            assert review['stock_state'] == 'NOT_POSTED'
            case['pending_result'] = {'stock_total': snapshot['company'][0]['total'], 'order_shipped': 6, 'second_shipped': 0, 'stock_unchanged_after_refusal': True, 'no_outbound_record': True}
            save(MANIFEST, case)
            print('PASS refusal: pending human review; remaining stock 4, order 6/10, no second stock command', flush=True)
            case['ui_result'] = browser_confirmation(case, credential, review)
        detail = multi.wait_for(lambda: multi.api(path, credential)['data'], lambda s: s['shipped_quantity'] == 4 and s['package_count'] == 1, 'Reviewed original callback not posted')
        assert len(detail['outbound_records']) == 1 and detail['outbound_records'][0]['state'] == 'COMPLETED'
        assert detail['packages'][0]['express_code'] == case['shared_waybill'] and detail['packages'][0]['shared_review_id'] == case['review_id']
        after = multi.stock(case); multi.verify_stock(case, after, 10)
        # Confirmation succeeds before any WMS resend. Only one refused callback is logged.
        logs = multi.api('/supplychain/wmsIntegration/logs?sn=' + second['shipment_sn'], credential)['data']
        callbacks = [r for r in logs if r['action'] == 'OUTBOUND_RECEIPT']
        if not case.get('replayed'):
            assert len(callbacks) == 1 and callbacks[0]['result'] == 'FAILED'
        multi.api(path + '/receipt-reviews/' + str(case['review_id']), credential, {'decision': 'CONFIRM_SHARED', 'control_version': review['control_version'], 'reason': '重复确认验收：沿用原处理结论'})
        send(second, connection, secret)
        multi.api(path + '/reconcile-receipts', credential, post=True)
        assert multi.stock(case) == after, 'Repeated review or signed callback deducted stock again'
        case['replayed'] = True
        reports = []
        for warehouse in case['warehouses']:
            reports.append(multi.wait_for(lambda: multi.api('/order/orders/shipments/' + str(warehouse['shipment_id']) + '/platform-shipping', credential)['data'], lambda r: len(r['tasks']) == warehouse['package_count'] and all(t['state'] == 'SUCCESS' for t in r['tasks']), 'Platform simulation tasks pending'))
        assert sum(t['quantity'] for r in reports for t in r['tasks']) == 10
        final = multi.api('/order/orders/business/' + str(case['order_id']), credential)['data']
        assert final['business_status'] == 'SHIPPED' and final['shipped_quantity'] == 10
        report = multi.api(path + '/receipt-reviews', credential)['data']['reviews'][0]
        assert report['state'] == 'CONFIRMED' and report['stock_state'] == 'POSTED' and report['operator_name'] == 'admin'
        case['completed'] = True; case['after_confirmation'] = after; save(MANIFEST, case)
        result = {'order_id': case['order_id'], 'order_sn': case['order_sn'], 'sku': case['sku'], 'review_id': case['review_id'], 'reviewed_shipment': second['shipment_sn'], 'shared_waybill': case['shared_waybill'], 'business_url': BASE + '/oms-order/business?view=business&orderId=' + str(case['order_id']), 'pending_result': case['pending_result'], 'confirmation_stock_total': 0, 'final_shipped': 10, 'final_packages': 3, 'platform_simulation_tasks': 3, 'warehouse_correction_or_resend_needed': False, 'duplicate_stock_deduction': False, 'review_actor': report['operator_name'], 'original_receipt_saved': True, 'real_platform_notification_sent': False, 'ui_result': case.get('ui_result')}
        (OUTPUT / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
